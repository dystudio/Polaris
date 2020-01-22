package com.polaris.demo;


import com.polaris.dubbo.annotation.PolarisDubboApplication;
import com.polaris.http.supports.MainSupport;

/**
 * 入口启动类
 *
 */
@PolarisDubboApplication
public class DemoApplication

{
    
    public static void main( String[] args ) throws Exception
    {

		//启动WEB
    	MainSupport.startServer(args,DemoApplication.class);
    }
}
