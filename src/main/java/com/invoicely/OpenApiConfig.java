package com.invoicely;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API for Swagger UI, served at {@code /swagger-ui.html}.
 *
 * <p>The bearer scheme is declared here so the UI shows an Authorize button.
 * Without it a reader could see every endpoint but call none of them, since
 * everything except registering, logging in and {@code /ping} needs a token.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI invoicelyOpenApi() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste the token from POST /auth/login or /auth/register.");

        return new OpenAPI()
                .info(new Info()
                        .title("Invoicely API")
                        .version("v1")
                        .description("""
                                Invoicing for a small Singapore business: clients, invoices with \
                                GST, payments, and a maker-checker approval lifecycle.

                                Two roles. STAFF build and submit draft invoices; OWNER approves \
                                and sends them, records payments, sees revenue, and manages the \
                                team. Every request is scoped to the business on the caller's \
                                token — an invoice belonging to another business answers 404, \
                                never 403, so its existence is not confirmed.

                                Errors are RFC 9457 Problem Details. The `type` field is stable \
                                and safe to branch on; `detail` is for people. Three failure \
                                axes are kept apart: 403 for the wrong role, 409 for the wrong \
                                invoice state, 404 for the wrong business."""))
                .components(new Components().addSecuritySchemes("bearer-jwt", bearer))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
