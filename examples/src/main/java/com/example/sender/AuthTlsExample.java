public class InsertExample {
    public static void main(String[] args) {
        try (Sender sender = Sender.builder()
                .address("clever-black-363-c1213c97.ilp.b04c.questdb.net:32074")
                .enableTls()
                .enableAuth("admin").authToken("GwBXoGG5c6NoUTLXnzMxw_uNiVa8PKobzx5EiuylMW0")
                .build()) {
            sender.table("sensor_data")
                    .tag("sensor_id", "sensor_001")
                    .timestampColumn("timestamp", System.currentTimeMillis())
                    .doubleColumn("temperature", 25.5)
                    .doubleColumn("humidity", 50.2)
                    .boolColumn("is_active", true)
                    .atNow();
        }
    }
}
