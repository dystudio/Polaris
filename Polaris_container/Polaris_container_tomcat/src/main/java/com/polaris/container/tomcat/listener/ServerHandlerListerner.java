package com.polaris.container.tomcat.listener;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.polaris.container.servlet.listener.ServerListener;

/**
 * Class Name : ServerHandler
 * Description : 服务器Handler
 * Creator : yufenghua
 * Modifier : yufenghua
 *
 */

public class ServerHandlerListerner implements LifecycleListener{
	
	private static final Logger logger = LoggerFactory.getLogger(ServerHandlerListerner.class);
	private ServerListener[] serverlisteners;
	
	public ServerHandlerListerner (ServerListener... serverlisteners) {
		this.serverlisteners = serverlisteners;
	}

	@Override
	public void lifecycleEvent(LifecycleEvent event) {
		// Process the event that has occurred
        if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
        } else if (event.getType().equals(Lifecycle.BEFORE_START_EVENT)) {
        	if (serverlisteners != null) {
        		for (ServerListener serverListener:serverlisteners) {
        			serverListener.starting();
        		}
        	}
        	
        	logger.info("TomcatServer启动中！");
        } else if (event.getType().equals(Lifecycle.AFTER_START_EVENT)) {
        	
        	if (serverlisteners != null) {
        		for (ServerListener serverListener:serverlisteners) {
        			serverListener.started();
        		}
        	}
        	logger.info("TomcatServer启动成功！");
        } else if (event.getType().equals(Lifecycle.BEFORE_STOP_EVENT)) {
        	if (serverlisteners != null) {
        		for (ServerListener serverListener:serverlisteners) {
        			serverListener.stopping();
        		}
        	}
        	logger.info("TomcatServer停止中！");
        } else if (event.getType().equals(Lifecycle.AFTER_STOP_EVENT)) {
        	if (serverlisteners != null) {
        		for (ServerListener serverListener:serverlisteners) {
        			serverListener.stopped();
        		}
        	}
        	logger.info("TomcatServer已经停止！");
        } else if (event.getType().equals(Lifecycle.AFTER_DESTROY_EVENT)) {
        }
		
	}
	
    
}
