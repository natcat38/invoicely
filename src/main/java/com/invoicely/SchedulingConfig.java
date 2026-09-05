package com.invoicely;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler so {@code @Scheduled} methods — currently just
 * {@link com.invoicely.domain.OverdueInvoices#flipSentInvoicesPastDueToOverdue()}
 * — actually run. Kept as its own file, separate from
 * {@link InvoicelyApplication}, so Task 5's overdue job doesn't need to touch
 * a file other in-flight work also depends on.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
