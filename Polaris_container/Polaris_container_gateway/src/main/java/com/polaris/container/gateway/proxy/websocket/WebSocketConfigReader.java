package com.polaris.container.gateway.proxy.websocket;

import com.polaris.container.gateway.HttpFileListener;
import com.polaris.container.gateway.HttpFileReader;
import com.polaris.container.gateway.pojo.HttpFile;
import com.polaris.core.pojo.KeyValuePair;
import com.polaris.core.util.PropertyUtil;
import com.polaris.core.util.StringUtil;

public class WebSocketConfigReader implements HttpFileListener{
    
    private static String fileName = "gw_ws.txt";
    
    private static int WS_REQUEST_MAX_NMBER = 2000;
    
    private static int WS_IDLE_CONNECT_TIMEOUT = 600;
    
    private static int WS_MAX_FRAME_PAYLOAD_LENGTH = 65536;
    
    static {
        new WebSocketConfigReader();
    }
    
    @Override
    public void onChange(HttpFile file) {
        for (String conf : file.getData()) {
            KeyValuePair kv = PropertyUtil.getKVPair(conf);
            if (kv != null && StringUtil.isNotEmpty(kv.getValue())) {
                if ("websocket.request.maxNumber".equals(kv.getKey())) {
                    try {
                        WS_REQUEST_MAX_NMBER = Integer.parseInt(kv.getValue());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    
                }
                if ("websocket.idle.connect.timeout".equals(kv.getKey())) {
                    try {
                        WS_IDLE_CONNECT_TIMEOUT = Integer.parseInt(kv.getValue());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                if ("websocket.maxFramePayloadLength".equals(kv.getKey())) {
                    try {
                        WS_MAX_FRAME_PAYLOAD_LENGTH = Integer.parseInt(kv.getValue());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }
    
    public WebSocketConfigReader() {
        HttpFileReader.INSTANCE.readFile(this, new HttpFile(fileName));
    }
    
    public static int getRequestMaxNumber() {
        return WS_REQUEST_MAX_NMBER;
    }
    public static int getIdleConnectTimeout() {
        return WS_IDLE_CONNECT_TIMEOUT;
    }
    public static int getMaxFramePayloadLength() {
        return WS_MAX_FRAME_PAYLOAD_LENGTH;
    }
}
