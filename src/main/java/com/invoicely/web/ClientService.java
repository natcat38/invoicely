package com.invoicely.web;

import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Client;
import com.invoicely.domain.ClientRepository;
import com.invoicely.domain.InvoiceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Client CRUD, scoped to the caller's business throughout — see ADR-0001.
 *
 * <p>The controller stays thin: it maps HTTP shapes to these calls and back.
 * Everything that is actually a rule about clients — the archive-instead-of-
 * delete guard, what counts as a matching search — lives here, so it is
 * enforced the same way regardless of which endpoint reaches it.
 *
 * <p>Like {@link InvoiceService}, every method returns a response record rather
 * than an entity, because {@code spring.jpa.open-in-view} is off: once the
 * transaction closes an entity can no longer load anything it has not already
 * loaded, so the mapping has to happen while it is still open.
 */
@Service
@Transactional
public class ClientService {

    private final ClientRepository clients;
    private final BusinessRepository businesses;
    private final InvoiceRepository invoices;
    private final CurrentRequest currentRequest;

    ClientService(ClientRepository clients,
                  BusinessRepository businesses,
                  InvoiceRepository invoices,
                  CurrentRequest currentRequest) {
        this.clients = clients;
        this.businesses = businesses;
        this.invoices = invoices;
        this.currentRequest = currentRequest;
    }

    public ClientResponse create(ClientRequest request) {
        Business business = businesses.findById(currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("Business"));
        Client client = new Client(business, request.name());
        applyOptionalFields(client, request);
        // A client is never born archived — that flag only matters on update.
        return ClientResponse.from(clients.save(client));
    }

    @Transactional(readOnly = true)
    public ClientResponse get(Long id) {
        return ClientResponse.from(load(id));
    }

    /**
     * The client list, alphabetical, showing one archive state at a time.
     *
     * @param search optional case-insensitive fragment of the client name
     */
    @Transactional(readOnly = true)
    public Page<ClientResponse> list(boolean archived, String search, Pageable pageable) {
        // The wildcards are added here rather than in the query, because a null
        // parameter that only ever appears inside concat() leaves PostgreSQL
        // guessing at its type — see ClientRepository.findForList.
        String namePattern = (search == null || search.isBlank())
                ? null
                : "%" + search.trim().toLowerCase() + "%";
        return clients.findForList(currentRequest.businessId(), archived, namePattern, pageable)
                .map(ClientResponse::from);
    }

    /** Full replace, including the archived flag — archiving has no endpoint of its own. */
    public ClientResponse update(Long id, ClientRequest request) {
        Client client = load(id);
        client.setName(request.name());
        applyOptionalFields(client, request);
        client.setArchived(Boolean.TRUE.equals(request.archived()));
        // No save() call: client is managed inside this transaction, so
        // Hibernate writes the changes back at commit.
        return ClientResponse.from(client);
    }

    public void delete(Long id) {
        Client client = load(id);
        if (invoices.existsByClientId(client.getId())) {
            throw new ConflictException("client-has-invoices",
                    "This client has invoices and cannot be deleted. Archive it instead.");
        }
        clients.delete(client);
    }

    /** The one ownership-scoped lookup every other method here goes through. */
    private Client load(Long id) {
        return clients.findByIdAndBusinessId(id, currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("Client"));
    }

    private void applyOptionalFields(Client client, ClientRequest request) {
        client.setContactPerson(request.contactPerson());
        client.setEmail(request.email());
        client.setPhone(request.phone());
        client.setAddress(request.address());
        client.setUen(request.uen());
        client.setPaymentNotes(request.paymentNotes());
    }
}
