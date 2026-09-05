package com.invoicely.web;

import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business's own settings: its name, whether it charges GST and at what
 * rate, and the payment terms new invoices default to.
 *
 * <p>Without this the GST feature would be unreachable — registration creates
 * every business as not GST-registered, and nothing else could ever change it.
 */
@Service
@Transactional
public class SettingsService {

    /**
     * Product Scope §5.2a offers 7, 14 or 30 days, and the V1 migration has a
     * CHECK constraint saying the same. Validated here so an unexpected value
     * is a readable 400 rather than the 500 a constraint violation would give.
     */
    private static final Set<Integer> ALLOWED_TERMS = Set.of(7, 14, 30);

    private final BusinessRepository businesses;
    private final CurrentRequest currentRequest;

    SettingsService(BusinessRepository businesses, CurrentRequest currentRequest) {
        this.businesses = businesses;
        this.currentRequest = currentRequest;
    }

    @Transactional(readOnly = true)
    SettingsResponse get() {
        return SettingsResponse.from(load());
    }

    SettingsResponse update(SettingsRequest request) {
        if (!ALLOWED_TERMS.contains(request.defaultPaymentTermsDays())) {
            throw new BadRequestException("unsupported-payment-terms",
                    "Payment terms must be 7, 14 or 30 days.");
        }

        Business business = load();
        business.setName(request.name());
        business.setGstRegistered(request.gstRegistered());
        // Stored whether or not the business is registered, so turning
        // registration back on later does not lose the rate.
        business.setGstRate(request.gstRate());
        business.setDefaultPaymentTermsDays(request.defaultPaymentTermsDays());
        // The letterhead (ADR-0011) is read live off this row by every invoice
        // document, sent or not — there is no snapshot to protect. That means
        // saving here changes what every already-sent invoice prints, today
        // and going forward. That is the deliberate trade the ADR makes
        // (fixing a typo should fix every invoice; the cost is that an old
        // invoice cannot be reprinted exactly as it was originally sent), not
        // an oversight of this method.
        business.setAddress(blankToNull(request.address()));
        business.setUen(blankToNull(request.uen()));
        // No save() call: business is managed inside this transaction, so
        // Hibernate writes the changes back at commit.
        return SettingsResponse.from(business);
    }

    /** The caller's own business, and only ever theirs — see ADR-0001. */
    private Business load() {
        return businesses.findById(currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("Business"));
    }

    /**
     * The letterhead fields are optional, so a Settings page that clears an
     * address field submits {@code ""} rather than omitting the field.
     * Normalising that to null here means the document only ever has to test
     * "is this field null" to decide whether to print the letterhead row,
     * instead of testing null and blank separately in two different places.
     */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
