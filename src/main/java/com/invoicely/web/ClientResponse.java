package com.invoicely.web;

import com.invoicely.domain.Client;

/**
 * A client as the API shows it: {@link ClientRequest}'s fields, plus the id
 * the caller never chooses.
 */
public record ClientResponse(
        Long id,
        String name,
        String contactPerson,
        String email,
        String phone,
        String address,
        String uen,
        String paymentNotes,
        boolean archived) {

    public static ClientResponse from(Client client) {
        return new ClientResponse(
                client.getId(),
                client.getName(),
                client.getContactPerson(),
                client.getEmail(),
                client.getPhone(),
                client.getAddress(),
                client.getUen(),
                client.getPaymentNotes(),
                client.isArchived());
    }
}
