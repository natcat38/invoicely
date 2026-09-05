package com.invoicely;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} turns on Spring's scheduler so {@code @Scheduled}
 * methods — currently just
 * {@link com.invoicely.domain.OverdueInvoices#flipSentInvoicesPastDueToOverdue()}
 * — actually run. Previously its own {@code SchedulingConfig} file, split out
 * so Task 5's overdue job wouldn't need to touch a file other in-flight work
 * also depended on; folded back in now that Tasks 1-6 are merged and that
 * concern no longer applies (ponytail-audit.md).
 */
@EnableScheduling
@SpringBootApplication
public class InvoicelyApplication {

	public static void main(String[] args) {
		SpringApplication.run(InvoicelyApplication.class, args);
	}

}
