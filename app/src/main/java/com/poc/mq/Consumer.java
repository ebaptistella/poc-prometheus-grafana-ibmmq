package com.poc.mq;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.concurrent.TimeUnit;

/**
 * Consome mensagens da fila configurada (default: DEV.QUEUE.1).
 * Encerra após timeout sem receber mensagem ou com Ctrl+C.
 * <p>
 * Uso:
 * <ul>
 *   <li>{@code Consumer [timeoutMs]} — imprime cada mensagem; encerra após timeoutMs sem mensagem.</li>
 *   <li>{@code Consumer [timeoutMs] [logEveryN]} — imprime só a cada N mensagens (modo dreno rápido). Ex.: {@code Consumer 3000 100}</li>
 * </ul>
 * Env: CONSUMER_TIMEOUT_MS (default 5s).
 */
public class Consumer {

    private static final long DEFAULT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    public static void main(String[] args) {
        String queueName = MqConfig.queueName();
        long timeoutMs = args.length > 0 ? Long.parseLong(args[0]) : defaultTimeoutMs();
        int logEveryN = args.length > 1 ? Integer.parseInt(args[1]) : 0; // 0 = logar todas

        try {
            var cf = MqConfig.createConnectionFactory();
            String user = System.getenv().getOrDefault("MQ_USER", MqConfig.DEFAULT_USER);
            String password = System.getenv().getOrDefault("MQ_PASSWORD", MqConfig.DEFAULT_PASSWORD);

            try (Connection conn = cf.createConnection(user, password);
                 Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {

                conn.start();
                Queue queue = session.createQueue(queueName);
                MessageConsumer consumer = session.createConsumer(queue);

                int received = 0;
                Message msg;
                while ((msg = consumer.receive(timeoutMs)) != null) {
                    if (logEveryN <= 0 || (received + 1) % logEveryN == 0) {
                        if (msg instanceof TextMessage) {
                            String text = ((TextMessage) msg).getText();
                            System.out.println("Recebida #" + (received + 1) + ": " + text);
                        } else {
                            System.out.println("Recebida #" + (received + 1) + " (não-texto): " + msg);
                        }
                    }
                    received++;
                }

                consumer.close();
                System.out.println("Consumer encerrado. " + received + " mensagem(ns) recebida(s) (timeout " + timeoutMs + " ms sem nova mensagem).");
            }
        } catch (JMSException e) {
            System.err.println("Erro JMS: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static long defaultTimeoutMs() {
        String v = System.getenv("CONSUMER_TIMEOUT_MS");
        if (v == null || v.isEmpty()) return DEFAULT_TIMEOUT_MS;
        return Long.parseLong(v);
    }
}
