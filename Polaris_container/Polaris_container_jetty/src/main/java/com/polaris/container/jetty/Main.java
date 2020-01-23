package com.polaris.container.jetty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.polaris.container.Server;
import com.polaris.container.jetty.server.JettyServer;
import com.polaris.container.servlet.listener.ServerListener;

/**
 * 入口启动类
 */
public class Main implements Server{

	private static Logger logger = LoggerFactory.getLogger(Main.class);
    /**
     * 服务启动
     *
     */
	@Override
	public void start(ServerListener listener) {
		//启动jetty
        new Thread(new Runnable() {
            @Override
            public void run() {
            	logger.info("jetty启动！");
                JettyServer server = JettyServer.getInstance();
                server.start(listener);
            }
        }).start();
		
	}
	
	/**
     * 服务关闭
     *
     */
	@Override
	public void stop() {
		//启动jetty
        new Thread(new Runnable() {
            @Override
            public void run() {
            	logger.info("jetty关闭！");
            	JettyServer server = JettyServer.getInstance();
                server.stop();
            }
        }).start();
        
	}
	
	/**
     * servlet上下文
     *
     */
	@Override
	public Object getContext() {
		JettyServer server = JettyServer.getInstance();
		return server.getServletContex();
    }
}
