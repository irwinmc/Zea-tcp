package com.akakata.app;

import com.akakata.event.Event;
import com.akakata.event.NetworkEvent;

import java.util.Set;

/**
 * 游戏接口
 * 包含了一个玩家会话列表
 *
 * @author Kyia
 */
public interface Game {

    /**
     * 构建玩家会话方法，可以用工厂方法替代
     *
     * @return 指定玩家会话
     */
    PlayerSession createPlayerSession();

    /**
     * 创建会话之后的登陆方法，给会话增加事件监听器
     *
     * @param playerSession 玩家会话
     */
    void onLogin(PlayerSession playerSession);

    /**
     * 新玩家连接到游戏
     *
     * @param playerSession 玩家会话
     * @return true 如果连接成功
     */
    boolean connectSession(PlayerSession playerSession);

    /**
     * 会话连接之后触发
     *
     * @param playerSession 玩家会话
     */
    void afterSessionConnect(PlayerSession playerSession);

    /**
     * 断开连接，将会话从该游戏的会话集合中删除
     *
     * @param playerSession 玩家会话
     * @return true 断开成功
     */
    boolean disconnectSession(PlayerSession playerSession);

    /**
     * 获取该游戏中所有玩家会话集合
     *
     * @return 会话集合
     */
    Set<PlayerSession> getSessions();

    /**
     * 事件处理
     *
     * @param event 消息事件
     */
    void send(Event event);

    /**
     * 广播消息，广播消息的处理方式和普通事件处理，区别在于事件类型
     *
     * @param networkEvent 消息事件
     */
    void sendBroadcast(NetworkEvent networkEvent);

    /**
     * 关闭游戏，断开所有连接到该游戏的玩家会话
     */
    void close();

    /**
     * 获取工厂
     *
     * @return 会话工厂
     */
    SessionFactory getFactory();

    /**
     * 设置工厂
     *
     * @param factory 会话工厂
     */
    void setFactory(SessionFactory factory);
}
