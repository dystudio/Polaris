package com.polaris.comm.config;

public interface ConfigHandler {
	String getKey(String env, String nameSpace, String cluster, String group, String key, boolean isWatch);
}
