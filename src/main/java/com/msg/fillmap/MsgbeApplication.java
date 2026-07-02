package com.msg.fillmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MsgbeApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsgbeApplication.class, args);
	}

}
