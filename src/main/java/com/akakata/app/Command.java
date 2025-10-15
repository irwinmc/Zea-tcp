package com.akakata.app;

import com.akakata.app.exception.InvalidParamException;
import com.akakata.communication.impl.RequestMessage;

/**
 * 游戏命令
 *
 * @author Kyia
 */
public interface Command {

    /**
     * 执行命令
     *
     * @param session 玩家的会话对象
     * @param request 请求，包含参数、通道信息
     * @throws InvalidParamException
     */
    void execute(PlayerSession session, RequestMessage request) throws InvalidParamException;
}
