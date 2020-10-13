package com.polaris.core.config.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.polaris.core.Constant;
import com.polaris.core.config.properties.ConfigurationPropertiesImport;

/**
 * An annotation for Polaris configuration Properties for binding POJO as Properties Object.
 *
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Configuration
@Import(ConfigurationPropertiesImport.class)
public @interface PolarisConfigurationProperties {

	/**
	 * The name prefix of the properties that are valid to bind to this object. Synonym
	 * for {@link #value()}. A valid prefix is defined by one or more words separated with
	 * dots (e.g. {@code "acme.system.feature"}).
	 * @return the name prefix of the properties to bind
	 */
	String prefix() default "";
    
    /**
     * auto-refreshed when configuration is changed.
     *
     * @return default value is <code>false</code>
     */
    boolean autoRefreshed() default true;
    
    /**
     * It indicates the properties of current doBind bean.
     *
     * @return default value is <code>false</code>
     */
    boolean bind() default false;
    
    /**
     * group.
     *
     * @return 
     */
    String group() default Constant.DEFAULT_GROUP;

    /**
     * value.
     *
     * @return 
     */
    String value() default "";
	
}
