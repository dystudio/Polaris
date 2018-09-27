package com.polaris.http.supports;

import com.polaris.comm.Constant;
import com.polaris.comm.config.ConfClient;
import com.polaris.core.connect.ServerDiscoveryHandlerProvider;
import com.polaris.http.factory.ContainerServerFactory;
import com.polaris.http.util.NetUtils;

/**
*
* 项目名称：Polaris_comm
* 类名称：MainSupport
* 类描述：
* 创建人：yufenghua
* 创建时间：2018年5月9日 上午8:55:18
* 修改人：yufenghua
* 修改时间：2018年5月9日 上午8:55:18
* 修改备注：
* @version
*
*/
public class MainSupport extends com.polaris.comm.supports.MainSupport{
	
	/**
	* 创建一个新的实例 MainSupport.
	*
	*/
	private MainSupport() {}

    
    /**
    * startWebServer(启动web容器)
    * @param 
    * @return 
    * @Exception 
    * @since 
    */
    public static void startWebServer(String[] args) {
    	
    	//参数初期化
    	iniParameter();
    	
    	//log4j重新设定地址
		configureAndWatch(Constant.WARCH_TIME);
		
		//注册服务
		ServerDiscoveryHandlerProvider.getInstance().register(NetUtils.getLocalHost(), Integer.parseInt(ConfClient.get(Constant.SERVER_PORT_NAME, false)));
		
		// add shutdown hook to stop server
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
            	ServerDiscoveryHandlerProvider.getInstance().deregister(NetUtils.getLocalHost(), Integer.parseInt(ConfClient.get(Constant.SERVER_PORT_NAME, false)));
            }
        });
		
    	//不允许重复启动
    	if (!makeSingle()) {
    		
        	//启动
        	ContainerServerFactory.newInstance();
    	} 
    }
}
