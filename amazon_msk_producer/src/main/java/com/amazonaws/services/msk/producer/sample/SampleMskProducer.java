/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazonaws.services.msk.producer.sample;
import com.amazonaws.services.schemaregistry.serializers.GlueSchemaRegistryKafkaSerializer;
import com.amazonaws.services.schemaregistry.utils.AWSSchemaRegistryConstants;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Properties;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Parser;
import java.io.IOException;
import java.io.File;
import java.util.Random;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

public class SampleMskProducer{
    private static final Properties properties = new Properties();
    private final static Logger LOGGER = LoggerFactory.getLogger(org.apache.kafka.clients.producer.Producer.class.getName());
    
    public static void main(String[] args) throws Exception {
        
        // Command line arguments
        Options options = new Options();
        options.addOption("region", true, "Specify region");
        options.addOption("brokers", true, "Specify broker");
        options.addOption("registryName", true, "Specify registry name");
        options.addOption("schema", true, "Specify schema name");
        options.addOption("numRecords", true, "specify the number of records");
        options.addOption("secretArn", true, "Specify the secretmanager ARN for username and password");
        options.addOption("topic", true, "Specify topic name");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        
        if (!cmd.hasOption("brokers") && !cmd.hasOption("secretArn") && !cmd.hasOption("region")) {
            throw new IllegalArgumentException("brokers, secretArn and region needs to be provided.");
        }
        String regionName = cmd.getOptionValue("region");
        String registryName = cmd.getOptionValue("registryName", "test-schema-registry");
        String schemaName = cmd.getOptionValue("schema", "test_payload_schema");
        String brokers = cmd.getOptionValue("brokers");
        String secretArn = cmd.getOptionValue("secretArn");
        String topic = cmd.getOptionValue("topic","test");
        int numOfRecords = Integer.parseInt(cmd.getOptionValue("numRecords", "10"));
        
        // Calling getSecret method to retrive credential from Secretmanager
        String getSecret = getSecret(secretArn,regionName);
        Object obj = JSONValue.parse(getSecret);
        JSONObject jsonObject = (JSONObject) obj;
        String username = (String) jsonObject.get("username");
        String password = (String) jsonObject.get("password");
        
        String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
        String jaasCfg = String.format(jaasTemplate, username, password);
        
        System.setProperty("software.amazon.awssdk.http.service.impl", "software.amazon.awssdk.http.urlconnection.UrlConnectionSdkHttpService");
        
        // Setting kafka properties 
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, GlueSchemaRegistryKafkaSerializer.class.getName());
        properties.put(AWSSchemaRegistryConstants.AWS_REGION, regionName);
        properties.put(AWSSchemaRegistryConstants.REGISTRY_NAME, registryName);
        properties.put(AWSSchemaRegistryConstants.SCHEMA_NAME, schemaName);
        properties.put(AWSSchemaRegistryConstants.DATA_FORMAT, "AVRO");
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        properties.put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512");
        properties.put(SaslConfigs.SASL_JAAS_CONFIG, jaasCfg);
        
        // Declearing and parsing the Schema fields for generic record builder.
        Schema schema_customer = null;
        try {
            schema_customer = new Parser().parse(new File("src/main/resources/avro/Customer.avsc"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        GenericRecord customer = new GenericData.Record(schema_customer);
        GenericRecord addressRecord = new GenericData.Record(schema_customer.getField("address").schema());
        LOGGER.info("Generic records phase completed...");
        
        Random rand = new Random();
        int zipcodeValue = 9999;
        int ageMaxValue = 100;
        
        // Initializing the Producer client, build records and publishing the message to the kafka broker  
        try (KafkaProducer<String, GenericRecord> producer = new KafkaProducer<>(properties)) {
            final ProducerRecord<String, GenericRecord> record = new ProducerRecord<String, GenericRecord>(topic, customer);
            LOGGER.info("Starting to send records...");
            for (int i = 0; i < numOfRecords; i++) {
                addressRecord.put("street", "city-" + i);
                addressRecord.put("zipcode", rand.nextInt(zipcodeValue));
                customer.put("Name","name-"+ i);
                customer.put("Age",rand.nextInt(ageMaxValue));
                customer.put("address", addressRecord);
                producer.send(record, new ProducerCallback());
            }
            producer.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Defining method for retrieving secret from Secretmanager
    public static String getSecret(String secretArn, String regionName) {
        
        String secretName = secretArn;
        Region region = Region.of(regionName);
        
        SecretsManagerClient client = SecretsManagerClient.builder()
        .region(region)
        .build();
        
        GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
        .secretId(secretName)
        .build();
        
        GetSecretValueResponse getSecretValueResponse;
        
        try {
            getSecretValueResponse = client.getSecretValue(getSecretValueRequest);
        } catch (Exception e) {
            
            throw e;
        }
        
        String secret = getSecretValueResponse.secretString();
        return(secret);
        
    }
    // Callback class for producer client for logging.     
    private static class ProducerCallback implements Callback {
        @Override
        public void onCompletion(RecordMetadata recordMetaData, Exception e) {
            if (e == null) {
                LOGGER.info("Received new metadata. \t" +
                "Topic:" + recordMetaData.topic() + "\t" +
                "Partition: " + recordMetaData.partition() + "\t" +
                "Offset: " + recordMetaData.offset() + "\t" +
                "Timestamp: " + recordMetaData.timestamp());
            } else {
                LOGGER.info("There's been an error from the Producer side");
                e.printStackTrace();
            }
        }
    }
}
