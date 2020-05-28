package com.polaris.core.component;

/**
 * The lifecycle interface for generic components.
 * <br>
 * Classes implementing this interface have a defined life cycle
 * defined by the methods of this interface.
 */
public interface LifeCycle {

	/**
     * Starts the component.
     *
     */
	void start();

	/**
     * Stops the component.
     * The component may wait for current activities to complete
     * normally, but it can be interrupted.
     *
     */
	void stop();
	
	/**
     * @return true if the component has been started.
     */
    boolean isStart();
    
	/**
     * @return true if the component has been stopped.
     */
    boolean isStop();
    
	/**
     * @return true if the component start failed.
     */
    boolean isFailed();
}
