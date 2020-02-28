package com.polaris.core.config.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.polaris.core.Constant;
import com.polaris.core.OrderWrapper;
import com.polaris.core.config.ConfClient;
import com.polaris.core.config.ConfHandler;
import com.polaris.core.config.ConfHandlerListener;
import com.polaris.core.config.Config;
import com.polaris.core.config.ConfigFactory;
import com.polaris.core.config.reader.CofReaderFactory;
import com.polaris.core.util.StringUtil;

@SuppressWarnings("rawtypes")
public class ConfHandlerProvider {
	private static final Logger logger = LoggerFactory.getLogger(ConfHandlerProvider.class);
	
    protected final ServiceLoader<ConfHandler> handlerLoader = ServiceLoader.load(ConfHandler.class);

	private volatile AtomicBoolean initialized = new AtomicBoolean(false);
	protected ConfHandler handler = handler();
	protected ConfHandler handler() {
		if (!initialized.compareAndSet(false, true)) {
            return handler;
        }
		List<OrderWrapper> configHandlerList = new ArrayList<OrderWrapper>();
    	for (ConfHandler configHandler : handlerLoader) {
    		OrderWrapper.insertSorted(configHandlerList, configHandler);
        }
    	if (configHandlerList.size() > 0) {
        	handler = (ConfHandler)configHandlerList.get(0).getHandler();
    	}
    	return handler;
    }

	
	//初始化操作
	public void init() {
		init(Config.EXTEND);
		init(Config.GLOBAL);
	}
	public String get(String fileName) {
		return get(fileName, ConfClient.getAppName());
	}
    public String get(String fileName, String group) {
		if (handler != null) {
			return handler.get(fileName, group);
		}
    	return null;
	}
	public void listen(String fileName, ConfHandlerListener listerner) {
		listen(fileName, ConfClient.getAppName(), listerner);
	}
    public void listen(String fileName,String group, ConfHandlerListener listener) {
		if (handler != null) {
			handler.listen(fileName, group, listener);
		}
	}

    private void init(String type) {
    	
		//获取配置
		Config config = ConfigFactory.get(type);
		
		//应用名称
		String group = Config.GLOBAL.equals(type) ? type : ConfClient.getAppName();
		
		//获取文件
		String files = type.equals(Config.EXTEND) ? 
				ConfigFactory.DEFAULT.get(Constant.PROJECT_EXTENSION_PROPERTIES) : 
					ConfigFactory.DEFAULT.get(Constant.PROJECT_GLOBAL_PROPERTIES);
		if (StringUtil.isEmpty(files)) {
			return;
		}
		String[] fileArray = files.split(",");
		
		//处理文件
		for (String file : fileArray) {
			//载入配置到缓存
			logger.info("{} load start",file);
			for (Map.Entry<Object, Object> entry : CofReaderFactory.get(file).getProperties(get(file,group)).entrySet()) {
				put(config, entry.getKey().toString(), entry.getValue().toString());
			}
			logger.info("{} load end",file);
			
	    	//增加监听
			logger.info("{} listen start",file);
	    	listen(file, group, new ConfHandlerListener() {
				@Override
				public void receive(String content) {
					for (Map.Entry<Object, Object> entry : CofReaderFactory.get(file).getProperties(content).entrySet()) {
						put(config, entry.getKey().toString(), entry.getValue().toString());
						listenForPut(config, entry.getKey().toString(), entry.getValue().toString());
					}
				}
			});
			logger.info("{} listen end",file);
		}
    }
    
    /**
	* 获取配置
	* @param 
	* @return 
	* @Exception 
	* @since 
	*/
	public String get(Config config,String key) {
		return config.get(key);
	}
	
    /**
	* 设置配置
	* @param 
	* @return 
	* @Exception 
	* @since 
	*/
    protected void put(Config config, String key, String value) {
    	config.put(key, value);
    }
    
    /**
	* 设置配置节点的监听
	* @param 
	* @return 
	* @Exception 
	* @since 
	*/
    protected void listenForPut(Config config, String key, String value){
    }
	
}
