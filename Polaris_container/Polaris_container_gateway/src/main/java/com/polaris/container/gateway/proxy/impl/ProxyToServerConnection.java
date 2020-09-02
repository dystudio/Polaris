package com.polaris.container.gateway.proxy.impl;

import static com.polaris.container.gateway.proxy.impl.ConnectionState.AWAITING_CHUNK;
import static com.polaris.container.gateway.proxy.impl.ConnectionState.AWAITING_INITIAL;
import static com.polaris.container.gateway.proxy.impl.ConnectionState.CONNECTING;
import static com.polaris.container.gateway.proxy.impl.ConnectionState.DISCONNECTED;
import static com.polaris.container.gateway.proxy.impl.ConnectionState.HANDSHAKING;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.RejectedExecutionException;

import javax.net.ssl.SSLProtocolException;

import com.barchart.udt.nio.SelectorProviderUDT;
import com.google.common.net.HostAndPort;
import com.polaris.container.gateway.proxy.ActivityTracker;
import com.polaris.container.gateway.proxy.FullFlowContext;
import com.polaris.container.gateway.proxy.HttpFilters;
import com.polaris.container.gateway.proxy.TransportProtocol;
import com.polaris.container.gateway.proxy.UnknownTransportProtocolException;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;

/**
 * <p>
 * Represents a connection from our proxy to a server on the web.
 * ProxyConnections are reused fairly liberally, and can go from disconnected to
 * connected, back to disconnected and so on.
 * </p>
 *
 * <p>
 * Connecting a {@link ProxyToServerConnection} can involve more than just
 * connecting the underlying {@link Channel}. In particular, the connection may
 * use encryption (i.e. TLS) and it may also establish an HTTP CONNECT tunnel.
 * The various steps involved in fully establishing a connection are
 * encapsulated in the property {@link #connectionFlow}, which is initialized in
 * {@link #initializeConnectionFlow()}.
 * </p>
 */
@Sharable
public class ProxyToServerConnection extends ProxyConnection<HttpResponse> {
    private final ClientToProxyConnection clientConnection;
    private final ProxyToServerConnection serverConnection = this;
    private volatile TransportProtocol transportProtocol;
    private volatile InetSocketAddress remoteAddress;
    private volatile InetSocketAddress localAddress;
    private final String serverHostAndPort;
    private final String contextPath;

    /**
     * The filters to apply to response/chunks received from server.
     */
    private volatile HttpFilters currentFilters;

    /**
     * Encapsulates the flow for establishing a connection, which can vary
     * depending on how things are configured.
     */
    private volatile ConnectionFlow connectionFlow;

    /**
     * Disables SNI when initializing connection flow in {@link #initializeConnectionFlow()}. This value is set to true
     * when retrying a connection without SNI to work around Java's SNI handling issue (see
     * {@link #connectionFailed(Throwable)}).
     */
    private volatile boolean disableSni = false;

    /**
     * While we're in the process of connecting, it's possible that we'll
     * receive a new message to write. This lock helps us synchronize and wait
     * for the connection to be established before writing the next message.
     */
    private final Object connectLock = new Object();

    /**
     * This is the initial request received prior to connecting. We keep track
     * of it so that we can process it after connection finishes.
     */
    private volatile HttpRequest initialRequest;

    /**
     * Keeps track of HttpRequests that have been issued so that we can
     * associate them with responses that we get back
     */
    private volatile HttpRequest currentHttpRequest;

    /**
     * While we're doing a chunked transfer, this keeps track of the initial
     * HttpResponse object for our transfer (which is useful for its headers).
     */
    private volatile HttpResponse currentHttpResponse;

    /**
     * Limits bandwidth when throttling is enabled.
     */
    private volatile GlobalTrafficShapingHandler trafficHandler;
    
    /**
     * flowContext
     */
    private volatile FullFlowContext flowContext;

    /**
     * Create a new ProxyToServerConnection.
     *
     * @param proxyServer
     * @param clientConnection
     * @param serverHostAndPort
     * @param initialFilters
     * @param initialHttpRequest
     * @return
     * @throws UnknownHostException
     */
    static ProxyToServerConnection create(DefaultHttpProxyServer proxyServer,
                                          ClientToProxyConnection clientConnection,
                                          String serverHostAndPort,
                                          String contextPath,
                                          HttpFilters initialFilters,
                                          HttpRequest initialHttpRequest,
                                          GlobalTrafficShapingHandler globalTrafficShapingHandler)
            throws UnknownHostException {
        return new ProxyToServerConnection(proxyServer,
                clientConnection,
                serverHostAndPort,
                contextPath,
                initialFilters,
                globalTrafficShapingHandler,
                initialHttpRequest
                );
    }

    private ProxyToServerConnection(
            DefaultHttpProxyServer proxyServer,
            ClientToProxyConnection clientConnection,
            String serverHostAndPort,
            String contextPath,
            HttpFilters initialFilters,
            GlobalTrafficShapingHandler globalTrafficShapingHandler,
            HttpRequest initialHttpRequest)
            throws UnknownHostException {
        super(DISCONNECTED, proxyServer);
        this.clientConnection = clientConnection;
        this.serverHostAndPort = serverHostAndPort;
        this.contextPath = contextPath;
        this.trafficHandler = globalTrafficShapingHandler;
        this.currentFilters = initialFilters;
        this.flowContext = new FullFlowContext(clientConnection,this);
        // Report connection status to HttpFilters
        currentFilters.proxyToServerConnectionQueued(flowContext);

        setupConnectionParameters();
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected void read(Object msg) {
        if (isConnecting()) {
            LOG.debug(
                    "In the middle of connecting, forwarding message to connection flow: {}",
                    msg);
            this.connectionFlow.read(msg);
        } else {
            super.read(msg);
        }
    }

	@Override
    protected ConnectionState readHTTPInitial(HttpResponse httpResponse) {
        LOG.debug("Received raw response: {}", httpResponse);

        if (httpResponse.decoderResult().isFailure()) {
            LOG.debug("Could not parse response from server. Decoder result: {}", httpResponse.decoderResult().toString());

            // create a "substitute" Bad Gateway response from the server, since we couldn't understand what the actual
            // response from the server was. set the keep-alive on the substitute response to false so the proxy closes
            // the connection to the server, since we don't know what state the server thinks the connection is in.
            FullHttpResponse substituteResponse = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_GATEWAY,
                    "Unable to parse response from server");
            HttpUtil.setKeepAlive(substituteResponse, false);
            httpResponse = substituteResponse;
        }

        currentFilters.serverToProxyResponseReceiving(flowContext);

        rememberCurrentResponse(httpResponse);
        respondWith(httpResponse);

        if (ProxyUtils.isChunked(httpResponse)) {
            return AWAITING_CHUNK;
        } else {
            currentFilters.serverToProxyResponseReceived(flowContext);

            return AWAITING_INITIAL;
        }
    }

    @Override
    protected void readHTTPChunk(HttpContent chunk) {
        respondWith(chunk);
    }

    @Override
    protected void readRaw(ByteBuf buf) {
        clientConnection.write(buf);
    }

    /**
     * <p>
     * Responses to HEAD requests aren't supposed to have content, but Netty
     * doesn't know that any given response is to a HEAD request, so it needs to
     * be told that there's no content so that it doesn't hang waiting for it.
     * </p>
     *
     * <p>
     * See the documentation for {@link HttpResponseDecoder} for information
     * about why HEAD requests need special handling.
     * </p>
     *
     * <p>
     * Thanks to <a href="https://github.com/nataliakoval">nataliakoval</a> for
     * pointing out that with connections being reused as they are, this needs
     * to be sensitive to the current request.
     * </p>
     */
    private class HeadAwareHttpResponseDecoder extends HttpResponseDecoder {

        public HeadAwareHttpResponseDecoder(int maxInitialLineLength,
                                            int maxHeaderSize, int maxChunkSize) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage httpMessage) {
            // The current HTTP Request can be null when this proxy is
            // negotiating a CONNECT request with a chained proxy
            // while it is running as a MITM. Since the response to a
            // CONNECT request does not have any content, we return true.
            if(currentHttpRequest == null) {
                return true;
            } else {
                return ProxyUtils.isHEAD(currentHttpRequest) || super.isContentAlwaysEmpty(httpMessage);
            }
        }
    };

    /***************************************************************************
     * Writing
     **************************************************************************/

    /**
     * Like {@link #write(Object)} and also sets the current filters to the
     * given value.
     *
     * @param msg
     * @param filters
     */
    void write(Object msg, HttpFilters filters) {
        this.currentFilters = filters;
        write(msg);
    }

    @Override
    void write(Object msg) {
        LOG.debug("Requested write of {}", msg);

        if (msg instanceof ReferenceCounted) {
            LOG.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }

        if (is(DISCONNECTED) && msg instanceof HttpRequest) {
            LOG.debug("Currently disconnected, connect and then write the message");
            connectAndWrite((HttpRequest) msg);
        } else {
            if (isConnecting()) {
                synchronized (connectLock) {
                    if (isConnecting()) {
                        LOG.debug("Attempted to write while still in the process of connecting, waiting for connection.");
                        clientConnection.stopReading();
                        try {
                            connectLock.wait(30000);
                        } catch (InterruptedException ie) {
                            LOG.warn("Interrupted while waiting for connect monitor");
                        }
                    }
                }
            }

            // only write this message if a connection was established and is not in the process of disconnecting or
            // already disconnected
            if (isConnecting() || getCurrentState().isDisconnectingOrDisconnected()) {
                LOG.debug("Connection failed or timed out while waiting to write message to server. Message will be discarded: {}", msg);
                return;
            }

            LOG.debug("Using existing connection to: {}", remoteAddress);
            doWrite(msg);
        }
    };

    @Override
    protected void writeHttp(HttpObject httpObject) {
        if (httpObject instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) httpObject;
            // Remember that we issued this HttpRequest for later
            currentHttpRequest = httpRequest;
        }
        super.writeHttp(httpObject);
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    @Override
    protected void become(ConnectionState newState) {
        // Report connection status to HttpFilters
        if (getCurrentState() == DISCONNECTED && newState == CONNECTING) {
            currentFilters.proxyToServerConnectionStarted(flowContext);
        } else if (getCurrentState() == CONNECTING) {
            if (newState == HANDSHAKING) {
                currentFilters.proxyToServerConnectionSSLHandshakeStarted(flowContext);
            } else if (newState == AWAITING_INITIAL) {
                currentFilters.proxyToServerConnectionSucceeded(flowContext);
            } else if (newState == DISCONNECTED) {
                currentFilters.proxyToServerConnectionFailed(flowContext);
            }
        } else if (getCurrentState() == HANDSHAKING) {
            if (newState == AWAITING_INITIAL) {
                currentFilters.proxyToServerConnectionSucceeded(flowContext);
            } else if (newState == DISCONNECTED) {
                currentFilters.proxyToServerConnectionFailed(flowContext);
            }
        } else if (getCurrentState() == AWAITING_CHUNK
                && newState != AWAITING_CHUNK) {
            currentFilters.serverToProxyResponseReceived(flowContext);
        }

        super.become(newState);
    }

    @Override
    protected void becameSaturated() {
        super.becameSaturated();
        this.clientConnection.serverBecameSaturated(this);
    }

    @Override
    protected void becameWritable() {
        super.becameWritable();
        this.clientConnection.serverBecameWriteable(this);
    }

    @Override
    protected void timedOut() {
        super.timedOut();
        clientConnection.timedOut(this);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        clientConnection.serverDisconnected(this);
    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        try {
            if (cause instanceof IOException) {
                // IOExceptions are expected errors, for example when a server drops the connection. rather than flood
                // the logs with stack traces for these expected exceptions, log the message at the INFO level and the
                // stack trace at the DEBUG level.
                LOG.info("An IOException occurred on ProxyToServerConnection: " + cause.getMessage());
                LOG.debug("An IOException occurred on ProxyToServerConnection", cause);
            } else if (cause instanceof RejectedExecutionException) {
                LOG.info("An executor rejected a read or write operation on the ProxyToServerConnection (this is normal if the proxy is shutting down). Message: " + cause.getMessage());
                LOG.debug("A RejectedExecutionException occurred on ProxyToServerConnection", cause);
            } else {
                LOG.error("Caught an exception on ProxyToServerConnection", cause);
            }
        } finally {
            if (!is(DISCONNECTED)) {
                LOG.info("Disconnecting open connection to server");
                disconnect();
            }
        }
        // This can happen if we couldn't make the initial connection due
        // to something like an unresolved address, for example, or a timeout.
        // There will not have been be any requests written on an unopened
        // connection, so there should not be any further action to take here.
    }

    /***************************************************************************
     * State Management
     **************************************************************************/
    public TransportProtocol getTransportProtocol() {
        return transportProtocol;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    public HttpRequest getInitialRequest() {
        return initialRequest;
    }
    public String getContextPath() {
    	return contextPath;
    }

    @Override
    protected HttpFilters getHttpFiltersFromProxyServer(HttpRequest httpRequest) {
        return currentFilters;
    }

    /***************************************************************************
     * Private Implementation
     **************************************************************************/

    /**
     * Keeps track of the current HttpResponse so that we can associate its
     * headers with future related chunks for this same transfer.
     *
     * @param response
     */
    private void rememberCurrentResponse(HttpResponse response) {
        LOG.debug("Remembering the current response.");
        // We need to make a copy here because the response will be
        // modified in various ways before we need to do things like
        // analyze response headers for whether or not to close the
        // connection (which may not happen for a while for large, chunked
        // responses, for example).
        currentHttpResponse = ProxyUtils.copyMutableResponseFields(response);
    }

    /**
     * Respond to the client with the given {@link HttpObject}.
     *
     * @param httpObject
     */
    private void respondWith(HttpObject httpObject) {
        clientConnection.respond(this, currentFilters, currentHttpRequest,
                currentHttpResponse, httpObject);
    }

    /**
     * Configures the connection to the upstream server and begins the {@link ConnectionFlow}.
     *
     * @param initialRequest the current HTTP request being handled
     */
    private void connectAndWrite(HttpRequest initialRequest) {
        LOG.debug("Starting new connection to: {}", remoteAddress);

        // Remember our initial request so that we can write it after connecting
        this.initialRequest = initialRequest;
        initializeConnectionFlow();
        connectionFlow.start();
    }

    /**
     * This method initializes our {@link ConnectionFlow} based on however this connection has been configured. If
     * the {@link #disableSni} value is true, this method will not pass peer information to the MitmManager when
     * handling CONNECTs.
     */
    private void initializeConnectionFlow() {
        this.connectionFlow = new ConnectionFlow(clientConnection, this,
                connectLock)
                .then(ConnectChannel);

        if (ProxyUtils.isCONNECT(initialRequest)) {
            connectionFlow.then(serverConnection.StartTunneling)
            .then(clientConnection.RespondCONNECTSuccessful)
            .then(clientConnection.StartTunneling);
        }
    }

    /**
     * Opens the socket connection.
     */
    private ConnectionFlowStep ConnectChannel = new ConnectionFlowStep(this,
            CONNECTING) {
        @Override
        boolean shouldExecuteOnEventLoop() {
            return false;
        }

		@Override
        protected Future<?> execute() {
            Bootstrap cb = new Bootstrap().group(proxyServer.getProxyToServerWorkerFor(transportProtocol));

            switch (transportProtocol) {
                case TCP:
                    LOG.debug("Connecting to server with TCP");
                    cb.channelFactory(new io.netty.channel.ChannelFactory<Channel>() {
                        @Override
                        public Channel newChannel() {
                            return new NioSocketChannel();
                        }
                    });
                    break;
                case UDT:
                    LOG.debug("Connecting to server with UDT");
                    cb.channelFactory(new io.netty.channel.ChannelFactory<Channel>() {
                        @Override
                        public Channel newChannel() {
                            return new NioSocketChannel(SelectorProviderUDT.STREAM);
                        }
                    })
                    .option(ChannelOption.SO_REUSEADDR, true);
                    
                    break;
                
                default:
                    throw new UnknownTransportProtocolException(transportProtocol);
            }

            cb.handler(new ChannelInitializer<Channel>() {
                protected void initChannel(Channel ch) throws Exception {
                    initChannelPipeline(ch.pipeline(), initialRequest);
                };
            });
            cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                    proxyServer.getConnectTimeout());

            if (localAddress != null) {
                return cb.connect(remoteAddress, localAddress);
            } else {
                return cb.connect(remoteAddress);
            }
        }
    };

    /**
     * Called when the connection to the server or upstream chained proxy fails. This method may return true to indicate
     * that the connection should be retried. If returning true, this method must set up the connection itself.
     *
     * @param cause the reason that our attempt to connect failed (can be null)
     * @return true if we are trying to fall back to another connection
     */
    protected boolean connectionFailed(Throwable cause)
            throws UnknownHostException {
        // unlike a browser, java throws an exception when receiving an unrecognized_name TLS warning, even if the server
        // sends back a valid certificate for the expected host. we can retry the connection without SNI to allow the proxy
        // to connect to these misconfigured hosts. we should only retry the connection without SNI if the connection
        // failure happened when SNI was enabled, to prevent never-ending connection attempts due to SNI warnings.
        if (!disableSni && cause instanceof SSLProtocolException) {
            // unfortunately java does not expose the specific TLS alert number (112), so we have to look for the
            // unrecognized_name string in the exception's message
            if (cause.getMessage() != null && cause.getMessage().contains("unrecognized_name")) {
                LOG.debug("Failed to connect to server due to an unrecognized_name SSL warning. Retrying connection without SNI.");

                // disable SNI, re-setup the connection, and restart the connection flow
                disableSni = true;
                resetConnectionForRetry();
                connectAndWrite(initialRequest);

                return true;
            }
        }

        // the connection issue wasn't due to an unrecognized_name error, or the connection attempt failed even after
        // disabling SNI. before falling back to a chained proxy, re-enable SNI.
        disableSni = false;
        LOG.info("Connection to upstream server failed", cause);

        // no chained proxy fallback or other retry mechanism available
        return false;
    }

    /**
     * Convenience method to prepare to retry this connection. Closes the connection's channel and sets up
     * the connection again using {@link #setupConnectionParameters()}.
     *
     * @throws UnknownHostException when {@link #setupConnectionParameters()} is unable to resolve the hostname
     */
    private void resetConnectionForRetry() throws UnknownHostException {
        // Remove ourselves as handler on the old context
        this.ctx.pipeline().remove(this);
        this.ctx.close();
        this.ctx = null;

        this.setupConnectionParameters();
    }

    /**
     * Set up our connection parameters based on server address and chained
     * proxies.
     *
     * @throws UnknownHostException when unable to resolve the hostname to an IP address
     */
    private void setupConnectionParameters() throws UnknownHostException {
        this.transportProtocol = TransportProtocol.TCP;

        // Report DNS resolution to HttpFilters
        this.remoteAddress = this.currentFilters.proxyToServerResolutionStarted(flowContext,serverHostAndPort);

        // save the hostname and port of the unresolved address in hostAndPort, in case name resolution fails
        String hostAndPort = null;
        try {
            if (this.remoteAddress == null) {
                hostAndPort = serverHostAndPort;
                this.remoteAddress = addressFor(serverHostAndPort, proxyServer);
            } else if (this.remoteAddress.isUnresolved()) {
                // filter returned an unresolved address, so resolve it using the proxy server's resolver
                hostAndPort = HostAndPort.fromParts(this.remoteAddress.getHostName(), this.remoteAddress.getPort()).toString();
                
                this.remoteAddress = proxyServer.getServerResolver().resolve(this.remoteAddress.getHostName(),
                        this.remoteAddress.getPort(),contextPath);
            }
        } catch (UnknownHostException e) {
            // unable to resolve the hostname to an IP address. notify the filters of the failure before allowing the
            // exception to bubble up.
            this.currentFilters.proxyToServerResolutionFailed(flowContext,hostAndPort);
            throw e;
        }
        this.currentFilters.proxyToServerResolutionSucceeded(flowContext,serverHostAndPort, this.remoteAddress);
        this.localAddress = proxyServer.getLocalAddress();
    }

    /**
     * Initialize our {@link ChannelPipeline} to connect the upstream server.
     * LittleProxy acts as a client here.
     *
     * A {@link ChannelPipeline} invokes the read (Inbound) handlers in
     * ascending ordering of the list and then the write (Outbound) handlers in
     * descending ordering.
     *
     * Regarding the Javadoc of {@link HttpObjectAggregator} it's needed to have
     * the {@link HttpResponseEncoder} or {@link HttpRequestEncoder} before the
     * {@link HttpObjectAggregator} in the {@link ChannelPipeline}.
     *
     * @param pipeline
     * @param httpRequest
     */
    private void initChannelPipeline(ChannelPipeline pipeline,
                                     HttpRequest httpRequest) {

        if (trafficHandler != null) {
            pipeline.addLast("global-traffic-shaping", trafficHandler);
        }

        pipeline.addLast("bytesReadMonitor", bytesReadMonitor);
        pipeline.addLast("bytesWrittenMonitor", bytesWrittenMonitor);

        pipeline.addLast("encoder", new HttpRequestEncoder());
        pipeline.addLast("decoder", new HeadAwareHttpResponseDecoder(
                proxyServer.getMaxInitialLineLength(),
                proxyServer.getMaxHeaderSize(),
                proxyServer.getMaxChunkSize()));

        // Enable aggregation for filtering if necessary
        int numberOfBytesToBuffer = proxyServer.getFiltersSource()
                .getMaximumResponseBufferSizeInBytes();
        if (numberOfBytesToBuffer > 0) {
            aggregateContentForFiltering(pipeline, numberOfBytesToBuffer);
        }

        pipeline.addLast("responseReadMonitor", responseReadMonitor);
        pipeline.addLast("requestWrittenMonitor", requestWrittenMonitor);

        // Set idle timeout
        pipeline.addLast(
                "idle",
                new IdleStateHandler(0, 0, proxyServer
                        .getIdleConnectionTimeout()));

        pipeline.addLast("handler", this);
    }

    /**
     * <p>
     * Do all the stuff that needs to be done after our {@link ConnectionFlow}
     * has succeeded.
     * </p>
     *
     * @param shouldForwardInitialRequest
     *            whether or not we should forward the initial HttpRequest to
     *            the server after the connection has been established.
     */
    void connectionSucceeded(boolean shouldForwardInitialRequest) {
        become(AWAITING_INITIAL);
        clientConnection.serverConnectionSucceeded(this,
                shouldForwardInitialRequest);

        if (shouldForwardInitialRequest) {
            LOG.debug("Writing initial request: {}", initialRequest);
            write(initialRequest);
        } else {
            LOG.debug("Dropping initial request: {}", initialRequest);
        }

        // we're now done with the initialRequest: it's either been forwarded to the upstream server (HTTP requests), or
        // completely dropped (HTTPS CONNECTs). if the initialRequest is reference counted (typically because the HttpObjectAggregator is in
        // the pipeline to generate FullHttpRequests), we need to manually release it to avoid a memory leak.
        if (initialRequest instanceof ReferenceCounted) {
            ((ReferenceCounted)initialRequest).release();
        }
    }

    /**
     * Build an {@link InetSocketAddress} for the given hostAndPort.
     *
     * @param hostAndPort String representation of the host and port
     * @param proxyServer the current {@link DefaultHttpProxyServer}
     * @return a resolved InetSocketAddress for the specified hostAndPort
     * @throws UnknownHostException if hostAndPort could not be resolved, or if the input string could not be parsed into
     *          a host and port.
     */
    private InetSocketAddress addressFor(String hostAndPort, DefaultHttpProxyServer proxyServer)
            throws UnknownHostException {
        HostAndPort parsedHostAndPort;
        try {
            parsedHostAndPort = HostAndPort.fromString(hostAndPort);
        } catch (IllegalArgumentException e) {
            // we couldn't understand the hostAndPort string, so there is no way we can resolve it.
            throw new UnknownHostException(hostAndPort);
        }
        String host = parsedHostAndPort.getHost();
        int port = parsedHostAndPort.getPortOrDefault(80);

        return proxyServer.getServerResolver().resolve(host, port, contextPath);
    }

    /***************************************************************************
     * Activity Tracking/Statistics
     *
     * We track statistics on bytes, requests and responses by adding handlers
     * at the appropriate parts of the pipeline (see initChannelPipeline()).
     **************************************************************************/
    private final BytesReadMonitor bytesReadMonitor = new BytesReadMonitor() {
        @Override
        protected void bytesRead(int numberOfBytes) {
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesReceivedFromServer(flowContext, numberOfBytes);
            }
        }
    };

    private ResponseReadMonitor responseReadMonitor = new ResponseReadMonitor() {
        @Override
        protected void responseRead(HttpResponse httpResponse) {
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.responseReceivedFromServer(flowContext, httpResponse);
            }
        }
    };

    private BytesWrittenMonitor bytesWrittenMonitor = new BytesWrittenMonitor() {
        @Override
        protected void bytesWritten(int numberOfBytes) {
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesSentToServer(flowContext, numberOfBytes);
            }
        }
    };

    private RequestWrittenMonitor requestWrittenMonitor = new RequestWrittenMonitor() {
        @Override
        protected void requestWriting(HttpRequest httpRequest) {
            try {
                for (ActivityTracker tracker : proxyServer
                        .getActivityTrackers()) {
                    tracker.requestSentToServer(flowContext, httpRequest);
                }
            } catch (Throwable t) {
                LOG.warn("Error while invoking ActivityTracker on request", t);
            }

            currentFilters.proxyToServerRequestSending(flowContext, httpRequest);
        }

        @Override
        protected void requestWritten(HttpRequest httpRequest) {
        }

        @Override
        protected void contentWritten(HttpContent httpContent) {
            if (httpContent instanceof LastHttpContent) {
                currentFilters.proxyToServerRequestSent(flowContext, (LastHttpContent)httpContent);
            }
        }
    };

}