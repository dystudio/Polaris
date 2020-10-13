package com.polaris.core.config;

public interface ConfigChangeNotifier {
	
	/**
	* 从配置中心获取到的配置按照策略 进行回调处理
	* @param  config 配置
	* @param  file 文件名称
    * @param  group 分组  默认ConfClient.getAppName()
	* @param  contents 获取的配置内容
    * @param  listeners 配置监听器 
	* @return 
	* @Exception 
	* @since 
	*/
	void notify(Config config, String gourp,String file,  String contents, ConfigChangeListener... listeners);
}
