package com.polaris.container.jetty.listener;

import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.polaris.container.listener.ServerListener;
import com.polaris.container.listener.ServerListenerSupport;
import com.polaris.container.servlet.listener.WebsocketListerner;

/**
 * Class Name : ServerHandler
 * Description : 服务器Handler
 * Creator : yufenghua
 * Modifier : yufenghua
 *
 */

public class ServerHandlerListerner extends AbstractLifeCycleListener{
	
	private static final Logger logger = LoggerFactory.getLogger(ServerHandlerListerner.class);
	/**
	 * 服务器监听器集合
	 */
	private ServerListener websocketListerner = new WebsocketListerner();

	public ServerHandlerListerner() {
	}

	/**
	 * 监听server的状态
	 * 启动中
	 */
	public void lifeCycleStarting(LifeCycle event) {
		websocketListerner.starting();
		ServerListenerSupport.starting();
    	logger.info("JettyServer启动中！");
	}
	
	/**
	 * 监听server的状态
	 * 启动结束
	 */
    public void lifeCycleStarted(LifeCycle event) {
    	websocketListerner.started();
		ServerListenerSupport.started();
    	//日志
    	logger.info("JettyServer启动成功！");
    }
    
	/**
	 * 监听server的状态
	 * 异常
	 */
    public void lifeCycleFailure(LifeCycle event,Throwable cause) {
    	websocketListerner.failure();
		ServerListenerSupport.failure();
    	logger.info("JettyServer启动失败！");
    }
    
	/**
	 * 监听server的状态
	 * 结束中
	 */
   public void lifeCycleStopping(LifeCycle event) {
	   websocketListerner.stopping();
       ServerListenerSupport.stopping();
	   logger.info("JettyServer已经中！");
   }
   
	/**
	 * 监听server的状态
	 * 结束
	 */
    public void lifeCycleStopped(LifeCycle event) {
    	websocketListerner.stopped();
		ServerListenerSupport.stopped();
    	logger.info("JettyServer已经停止！");
    }
    
}
