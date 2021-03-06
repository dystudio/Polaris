package com.polaris.core.config.spring.value;

import com.polaris.core.config.ConfigChangeListener;
import com.polaris.core.config.provider.Config.Opt;
import com.polaris.core.util.SpringUtil;

public class SpringValueEndPoint implements ConfigChangeListener{
	@Override
	public void onChange(Object key, Object value, Opt opt) {
		SpringAutoUpdateConfigChangeListener listener = SpringUtil.getBean(SpringAutoUpdateConfigChangeListener.class);
		if (listener != null) {
			listener.onChange(key.toString());
		}
	}

}
