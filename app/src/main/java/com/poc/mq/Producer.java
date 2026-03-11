package com.poc.mq;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Envia mensagens de texto para a fila configurada (default: DEV.QUEUE.1).
 * <p>
 * Uso:
 * <ul>
 *   <li>N mensagens e sai: {@code Producer [N]} (default 5)</li>
 *   <li>Modo aleatório (janelas): {@code Producer random [batchSize] [minSec] [maxSec]}</li>
 *     Envia batchSize mensagens, espera um tempo aleatório entre minSec e maxSec, repete.
 *     Env: PRODUCER_BATCH_SIZE (default 100), PRODUCER_INTERVAL_MIN, PRODUCER_INTERVAL_MAX (default 3 e 15).
 * </ul>
 */
public class Producer {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_INTERVAL_MIN_SEC = 3;
    private static final int DEFAULT_INTERVAL_MAX_SEC = 15;

    public static void main(String[] args) {
        String queueName = MqConfig.queueName();
        boolean randomMode = args.length > 0 && "random".equalsIgnoreCase(args[0]);

        if (randomMode) {
            int batchSize = intFromEnvOrArgs("PRODUCER_BATCH_SIZE", args, 1, DEFAULT_BATCH_SIZE);
            int minSec = intFromEnvOrArgs("PRODUCER_INTERVAL_MIN", args, 2, DEFAULT_INTERVAL_MIN_SEC);
            int maxSec = intFromEnvOrArgs("PRODUCER_INTERVAL_MAX", args, 3, DEFAULT_INTERVAL_MAX_SEC);
            if (minSec > maxSec) { int t = minSec; minSec = maxSec; maxSec = t; }
            runRandomLoop(queueName, batchSize, minSec, maxSec);
        } else {
            int count = args.length > 0 ? Integer.parseInt(args[0]) : 5;
            runBatch(queueName, count);
        }
    }

    private static int intFromEnvOrArgs(String envKey, String[] args, int argIndex, int defaultVal) {
        String env = System.getenv(envKey);
        if (env != null && !env.isEmpty()) return Integer.parseInt(env.trim());
        if (args.length > argIndex) return Integer.parseInt(args[argIndex]);
        return defaultVal;
    }

    private static void runRandomLoop(String queueName, int batchSize, int minSec, int maxSec) {
        Random rnd = new Random();
        try {
            var cf = MqConfig.createConnectionFactory();
            String user = System.getenv().getOrDefault("MQ_USER", MqConfig.DEFAULT_USER);
            String password = System.getenv().getOrDefault("MQ_PASSWORD", MqConfig.DEFAULT_PASSWORD);

            try (Connection conn = cf.createConnection(user, password);
                 Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {

                conn.start();
                Queue queue = session.createQueue(queueName);
                MessageProducer producer = session.createProducer(queue);

                long totalCount = 0;
                int window = 0;
                System.out.println("Producer em modo aleatório: " + batchSize + " mensagens por janela, intervalo " + minSec + "–" + maxSec + " s entre janelas (Ctrl+C para parar).");

                while (true) {
                    window++;
                    for (int i = 0; i < batchSize; i++) {
                        totalCount++;
                        String text = "POC MQ [" + Instant.now() + "] janela " + window + " msg #" + (i + 1) + "/" + batchSize;
                        TextMessage msg = session.createTextMessage(text);
                        producer.send(msg);
                        if ((i + 1) % 50 == 0 || i == batchSize - 1) {
                            System.out.println("Janela " + window + ": enviadas " + (i + 1) + "/" + batchSize + " (total " + totalCount + ")");
                        }
                    }

                    int delaySec = minSec + (maxSec > minSec ? rnd.nextInt(maxSec - minSec + 1) : 0);
                    System.out.println("Aguardando " + delaySec + " s até próxima janela...");
                    TimeUnit.SECONDS.sleep(delaySec);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Producer interrompido.");
        } catch (JMSException e) {
            System.err.println("Erro JMS: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runBatch(String queueName, int count) {
        try {
            var cf = MqConfig.createConnectionFactory();
            String user = System.getenv().getOrDefault("MQ_USER", MqConfig.DEFAULT_USER);
            String password = System.getenv().getOrDefault("MQ_PASSWORD", MqConfig.DEFAULT_PASSWORD);

            try (Connection conn = cf.createConnection(user, password);
                 Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {

                conn.start();
                Queue queue = session.createQueue(queueName);
                MessageProducer producer = session.createProducer(queue);

                for (int i = 0; i < count; i++) {
                    String text = "POC MQ [" + Instant.now() + "] mensagem #" + (i + 1);
                    TextMessage msg = session.createTextMessage(text);
                    producer.send(msg);
                    System.out.println("Enviada: " + text);
                }

                producer.close();
            }
            System.out.println("Producer concluído. " + count + " mensagem(ns) enviada(s) para " + queueName);
        } catch (JMSException e) {
            System.err.println("Erro JMS: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
