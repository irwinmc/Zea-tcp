package com.akakata.app;

/**
 * @author Kelvin
 */
public interface GameCommandInterpreter {

    /**
     * 处理命令的方法
     *
     * @param command 命令
     * @throws Exception 除了无效参数、无效命令等其他额外的报错类型
     */
    void interpretCommand(Object command) throws Exception;
}
