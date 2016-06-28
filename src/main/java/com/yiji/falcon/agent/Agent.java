/*
 * www.yiji.com Inc.
 * Copyright (c) 2016 All Rights Reserved
 */
package com.yiji.falcon.agent;

import com.yiji.falcon.agent.config.AgentConfiguration;
import com.yiji.falcon.agent.jmx.JMXConnection;
import com.yiji.falcon.agent.plugins.JDBCPlugin;
import com.yiji.falcon.agent.plugins.PluginExecute;
import com.yiji.falcon.agent.plugins.PluginLibraryHelper;
import org.apache.log4j.PropertyConfigurator;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.impl.DirectSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Collection;

/*
 * 修订记录:
 * guqiu@yiji.com 2016-06-22 17:48 创建
 */

/**
 * agent服务
 * @author guqiu@yiji.com
 */
public class Agent extends Thread{

    public static final PrintStream OUT = System.out;
    public static final PrintStream ERR = System.err;

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private ServerSocketChannel serverSocketChannel;
    private static final Charset charset = Charset.forName("UTF-8");

    @Override
    public void run() {
        try {
            this.serverStart(AgentConfiguration.INSTANCE.getAgentPort());
        } catch (IOException e) {
            log.error("Agent启动失败",e);
        }
    }

    /**
     * 启动agent服务
     * @param port
     * @throws IOException
     */
    private void serverStart(int port) throws IOException {
        if(serverSocketChannel == null){
            // 创建对象
            log.info("开启服务");
            serverSocketChannel = ServerSocketChannel.open();
            // 使在相同主机上，关机此服务器后，再次启动依然绑定相同的端口
            serverSocketChannel.socket().setReuseAddress(true);
        }
        log.info("绑定端口" + port);
        serverSocketChannel.socket().bind(new InetSocketAddress(port));

        work();

        //阻塞式方式进行客户端连接操作,连接成功后,单独启动线程进行客户端的读写操作
        for(;;){
            try {
                SocketChannel socketChannel = serverSocketChannel.accept();
                log.info("客户端连接成功：" + socketChannel.toString());

                //启动线程,进行客户端的对话操作
                Thread talk = new Talk(socketChannel,this);
                talk.setName(socketChannel.toString());
                talk.start();
            } catch (IOException e) {
                break;
            }
        }

    }

    /**
     * 关闭服务器
     */
    void shutdown() throws IOException {
        log.info("正在关闭服务器");
        serverSocketChannel.close();
        log.info("------------进行调度器关闭处理-------------------");
        SchedulerFactory sf = DirectSchedulerFactory.getInstance();
        //获取所有的Scheduler
        Collection<Scheduler> schedulers;
        try {
            schedulers = sf.getAllSchedulers();
            for (Scheduler scheduler : schedulers) {
                try {
                    log.info("关闭调度器scheduler：" + scheduler.getSchedulerName());
                    scheduler.shutdown(true);
                } catch (SchedulerException e) {
                    log.warn("关闭调度器出现异常",e);
                    try {
                        log.warn("调度器scheduler：" + scheduler.getSchedulerName() + "\n状态：started:" + scheduler.isStarted() +
                                " shutdown:" + scheduler.isShutdown());
                    } catch (SchedulerException ignored) {
                    }
                }
            }
        } catch (SchedulerException e) {
            log.warn("获取Schedulers发生异常：" + e);
            log.error("获取Schedulers发生异常 " + e.getMessage());
        }
        log.info("调度器关闭成功");

        log.info("关闭JMX连接");
        JMXConnection.close();
        log.info("关闭数据库连接");
        PluginLibraryHelper.getJDBCPlugins().forEach(plugin -> {
            try {
                JDBCPlugin jdbcPlugin = (JDBCPlugin) plugin;
                jdbcPlugin.close();
            } catch (Exception e) {
                log.error("数据库关闭异常",e);
            }
        });

        log.info("服务器关闭成功");
        System.exit(0);
    }

    /**
     * 监控服务启动
     */
    private void work(){
        try {
            //注册插件
            new PluginLibraryHelper().register();
            //运行插件
            PluginExecute.start();
        } catch (Exception e) {
            log.error("Agent启动失败",e);
            System.exit(0);
        }
    }

    static String decode(ByteBuffer buffer){
        CharBuffer charBuffer = charset.decode(buffer);
        return charBuffer.toString();
    }

    static ByteBuffer encode(String str){
        return charset.encode(str);
    }

    public static void main(String[] args){
        String errorMsg = "Syntax: program < start | stop | status >";
        if(args.length < 1 ||
                        !("start".equals(args[0]) ||
                                "stop".equals(args[0]) ||
                                "status".equals(args[0])
                        ))
        {
            ERR.println(errorMsg);
            return;
        }

        switch (args[0]){
            case "start":
                //自定义日志配置文件
                PropertyConfigurator.configure(AgentConfiguration.INSTANCE.getLog4JConfPath());
                Thread main = new Agent();
                main.setName("FalconAgent");
                main.start();
                break;
            case "stop":
                try {
                    Client client = new Client();
                    client.start(AgentConfiguration.INSTANCE.getAgentPort());
                    client.sendCloseCommend();
                    client.talk();
                } catch (IOException e) {
                    OUT.println("Agent Not Start");
                }
                break;
            case "status":
                try {
                    Client client = new Client();
                    client.start(AgentConfiguration.INSTANCE.getAgentPort());
                    OUT.println("Agent Started On Port " + AgentConfiguration.INSTANCE.getAgentPort());
                    client.close();
                } catch (IOException e) {
                    OUT.println("Agent Not Start");
                }
                break;
            default:
                OUT.println(errorMsg);
        }

    }

}
