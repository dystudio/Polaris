package com.polaris.container.gateway.request;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.polaris.container.gateway.support.HttpRequestFilterSupport;
import com.polaris.core.Constant;
import com.polaris.core.config.ConfHandlerProvider;
import com.polaris.core.config.ConfHandlerSupport;
import com.polaris.core.config.ConfListener;
import com.polaris.core.util.JwtUtil;
import com.polaris.core.util.StringUtil;
import com.polaris.core.util.SystemCallUtil;

import cn.hutool.core.util.StrUtil;
import io.jsonwebtoken.Claims;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;

/**
 * @author:Tom.Yu <p>
 * Description:
 * <p>
 * Token拦截
 */
@Service
public class TokenHttpRequestFilter extends HttpRequestFilter {

	public volatile static Set<String> UNCHECKED_PATHS = new HashSet<>();
	public volatile static Set<String> UNCHECKED_PATHS_PREFIX = new HashSet<>();
	public volatile static Set<String> TOKEN_PATH = new HashSet<>();
	private final static String FILE_NAME = "token.txt";
	public final static String TOKEN_MESSAGE="认证失败，请先登录";
	public final static String DEFAULT_VALUE = "1";

	static {
		//先获取
		loadFile(ConfHandlerProvider.INSTANCE.get(FILE_NAME));
		
		//后监听
		ConfHandlerProvider.INSTANCE.listen(FILE_NAME, new ConfListener() {
			@Override
			public void receive(String content) {
				loadFile(content);
			}
    	});
    }
    
    private static void loadFile(String content) {
    	if (StringUtil.isEmpty(content)) {
    		UNCHECKED_PATHS = new HashSet<>();
    		UNCHECKED_PATHS_PREFIX = new HashSet<>();
    		TOKEN_PATH = new HashSet<>();
    		return;
    	}
    	String[] contents = content.split(Constant.LINE_SEP);
    	Set<String> UNCHECKED_PATHS_TEMP = new HashSet<>();
    	Set<String> UNCHECKED_PATHS_PREFIX_TEMP = new HashSet<>();
    	Set<String> TOKEN_PATH_TEMP = new HashSet<>();
 
    	for (String conf : contents) {
    		if (StringUtil.isNotEmpty(conf) && !conf.startsWith("#")) {
    			conf = conf.replace("\n", "");
    			conf = conf.replace("\r", "");

				String[] kv = ConfHandlerSupport.getKeyValue(conf);

				// 不需要验证token的uri
    			if (kv[0].equals("UNCHECKED_PATHS")) {
    				UNCHECKED_PATHS_TEMP.add(kv[1]);
    			}
    			
    			// 不需要验证token的uri前缀，一般为context
    			if (kv[0].equals("UNCHECKED_PATHS_PREFIX")) {
    				UNCHECKED_PATHS_PREFIX_TEMP.add(kv[1]);
    			}
    			
    			//tokenUrl
    			if (kv[0].equals("TOKEN_PATH")) {
    				TOKEN_PATH_TEMP.add(kv[1]);
    			}
    		}
    	}
    	UNCHECKED_PATHS_PREFIX = UNCHECKED_PATHS_PREFIX_TEMP;
    	UNCHECKED_PATHS = UNCHECKED_PATHS_TEMP;
    	TOKEN_PATH = TOKEN_PATH_TEMP;
    }
    
    //获取url
    public static String getUrl(HttpRequest httpRequest) {
        return getUrl(httpRequest, null);
    }
    public static String getUrl(HttpRequest httpRequest, Map<String, String> parameterMap) {
    	//获取url
        String uri = httpRequest.uri();
        String url;
        int index = uri.indexOf("?");
        if (index > 0) {
            url = uri.substring(0, index);
            if (parameterMap != null) {
            	String strParameter = uri.substring(index + 1);
            	String[] parameters = strParameter.split("&");
            	for (String parameter : parameters) {
            		String[] kv = ConfHandlerSupport.getKeyValue(parameter);
            		parameterMap.put(kv[0], kv[1]);
            	}
            }
        } else {
            url = uri;
        }
        return url;
    }
    
    //验证url
    public static boolean checkUrlPath(String url) {
        
        //验证是否需要放过
        for (String context : UNCHECKED_PATHS_PREFIX) {
        	if (url.startsWith(context)) {
        		return false;
        	}
        }
        if (UNCHECKED_PATHS.contains(url)) {
        	return false;
        }
        return true;
    }
    
    //验证url
    public static boolean isSystemCall(HttpRequest httpRequest) {
    	String value = httpRequest.headers().get(SystemCallUtil.key());
    	return SystemCallUtil.verify(value);
    }

    
    public static boolean isTokenPath(String url) {
    	return TOKEN_PATH.contains(url);
    }
    
	@Override
    public boolean doFilter(HttpRequest originalRequest, HttpObject httpObject, ChannelHandlerContext channelHandlerContext) {
        if (httpObject instanceof HttpRequest) {
        	
            //获取request
            HttpRequest httpRequest = (HttpRequest) httpObject;
            
            //检验系统调用
            if (isSystemCall(httpRequest)) {
                return false;
            }

            //认证
            String token = httpRequest.headers().get(Constant.TOKEN_ID);
            httpRequest.headers().set(Constant.TOKEN_ID, DEFAULT_VALUE);

            //没有token需要验证url是否放过
            if (StringUtil.isEmpty(token)) {
                boolean checkResult = checkUrlPath(getUrl(httpRequest));
                if (!checkResult) {
                	return false;
                }
            	this.setResultDto(HttpRequestFilterSupport.createResultDto(Constant.TOKEN_FAIL_CODE,TOKEN_MESSAGE));
                return true;
            }

            try {
            	
            	//token认证
                Claims claims = JwtUtil.parseJWT(token);
                if (claims == null) {
                	this.setResultDto(HttpRequestFilterSupport.createResultDto(Constant.TOKEN_FAIL_CODE,TOKEN_MESSAGE));
                    return true;
                }
                String userName = claims.getSubject();
                if (StrUtil.isEmpty(userName)) {
                	this.setResultDto(HttpRequestFilterSupport.createResultDto(Constant.TOKEN_FAIL_CODE,TOKEN_MESSAGE));
                    return true;
                }
                
                //设置claims信息
                httpRequest.headers().add(JwtUtil.CLAIMS_KEY, JwtUtil.encode(claims));
                httpRequest.headers().add(SystemCallUtil.key(), SystemCallUtil.value());
                return false;
            } catch (Exception ex) {
            	this.setResultDto(HttpRequestFilterSupport.createResultDto(Constant.TOKEN_FAIL_CODE,TOKEN_MESSAGE));
                return true;
            }

        }
        return false;
    }
}

