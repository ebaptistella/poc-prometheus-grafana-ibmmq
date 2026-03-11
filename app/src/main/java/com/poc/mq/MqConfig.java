package com.poc.mq;

import com.ibm.msg.client.jms.JmsConnectionFactory;
import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;

import javax.jms.JMSException;
import java.util.Optional;

/**
 * Configuração e criação de ConnectionFactory para o IBM MQ (filas DEV.* do container de desenvolvimento).
 */
public final class MqConfig {

    public static final String DEFAULT_QUEUE_MANAGER = "QM1";
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 1414;
    public static final String DEFAULT_CHANNEL = "DEV.APP.SVRCONN";
    public static final String DEFAULT_QUEUE = "DEV.QUEUE.1";
    public static final String DEFAULT_USER = "app";
    public static final String DEFAULT_PASSWORD = "passw0rd";

    private MqConfig() {}

    public static JmsConnectionFactory createConnectionFactory() throws JMSException {
        return createConnectionFactory(
                env("MQ_QMGR", DEFAULT_QUEUE_MANAGER),
                env("MQ_HOST", DEFAULT_HOST),
                Integer.parseInt(env("MQ_PORT", String.valueOf(DEFAULT_PORT))),
                env("MQ_CHANNEL", DEFAULT_CHANNEL),
                env("MQ_USER", DEFAULT_USER),
                env("MQ_PASSWORD", DEFAULT_PASSWORD)
        );
    }

    public static JmsConnectionFactory createConnectionFactory(
            String queueManager,
            String host,
            int port,
            String channel,
            String user,
            String password
    ) throws JMSException {
        JmsFactoryFactory ff = JmsFactoryFactory.getInstance(WMQConstants.WMQ_PROVIDER);
        JmsConnectionFactory cf = ff.createConnectionFactory();

        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, queueManager);
        cf.setStringProperty(WMQConstants.WMQ_CONNECTION_NAME_LIST, host + "(" + port + ")");
        cf.setStringProperty(WMQConstants.WMQ_CHANNEL, channel);
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        cf.setStringProperty(WMQConstants.USERID, user);
        cf.setStringProperty(WMQConstants.PASSWORD, password);

        return cf;
    }

    public static String queueName() {
        return env("MQ_QUEUE", DEFAULT_QUEUE);
    }

    private static String env(String name, String defaultValue) {
        return Optional.ofNullable(System.getenv(name)).orElse(defaultValue);
    }
}
