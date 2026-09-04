package com.invoicely.web;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client endpoints. Like {@link InvoiceController}, this class does HTTP
 * and nothing else; the rules live in {@link ClientService}.
 */
@RestController
@RequestMapping("/clients")
class ClientController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ClientService clientService;

    ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    @PostMapping
    ResponseEntity<ClientResponse> create(@Valid @RequestBody ClientRequest request) {
        ClientResponse created = clientService.create(request);
        return ResponseEntity.created(URI.create("/clients/" + created.id())).body(created);
    }

    /**
     * The client list, alphabetical.
     *
     * <p>{@code archived} defaults to false, so the default view hides archived
     * clients (Product Scope §5.2); passing true shows only the archived ones.
     * The two states are never mixed into one page, which keeps "restore this
     * client" and "who am I invoicing" as separate screens rather than one list
     * the caller has to filter for themselves.
     *
     * @param q optional case-insensitive fragment of the client name
     */
    @GetMapping
    Page<ClientResponse> list(
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return clientService.list(archived, q, pageRequest);
    }

    @GetMapping("/{id}")
    ClientResponse get(@PathVariable Long id) {
        return clientService.get(id);
    }

    /**
     * Full replace, including {@code archived} — this is also how a client gets
     * archived or restored, since there is no separate endpoint for it.
     */
    @PutMapping("/{id}")
    ClientResponse update(@PathVariable Long id, @Valid @RequestBody ClientRequest request) {
        return clientService.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable Long id) {
        clientService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
