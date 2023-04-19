package com.example.sender;

import io.questdb.client.Sender;

public class AuthTlsExample {
    public static void main(String[] args) {
        try (Sender sender = Sender.builder()
                .address("clever-black-363-c1213c97.ilp.b04c.questdb.net:32074")
                .enableTls()
                .enableAuth("admin").authToken("GwBXoGG5c6NoUTLXnzMxw_uNiVa8PKobzx5EiuylMW0")
                .build()) {
            sender.table("inventors")
                    .symbol("born", "Austrian Empire")
                    .longColumn("id", 0)
                    .stringColumn("name", "Nicola Tesla")
                    .atNow();
            sender.table("inventors")
                    .symbol("born", "USA")
                    .longColumn("id", 1)
                    .stringColumn("name", "Thomas Alva Edison")
                    .atNow();
        }
    }
}
import io.questdb.*;

public class Quest {
    public static void main(String[] args) {
        try (Journal journal = new JournalFactory().reader("example_journal")) {
            JournalEntryReader reader = journal.select("select * from table_name");

            while (reader.hasNext()) {
                System.out.println(reader.getRecord());
            }
        } catch (JournalException e) {
            e.printStackTrace();
        }
    }
}
