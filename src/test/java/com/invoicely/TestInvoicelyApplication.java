package com.invoicely;

import org.springframework.boot.SpringApplication;

public class TestInvoicelyApplication {

	public static void main(String[] args) {
		SpringApplication.from(InvoicelyApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
