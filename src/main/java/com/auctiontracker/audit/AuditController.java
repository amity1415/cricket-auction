package com.auctiontracker.audit;

import com.auctiontracker.sale.Sale;
import com.auctiontracker.sale.SaleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Chronological sale/unsold history (DESIGN.md 6.1: GET /api/admin/audit). */
@RestController
public class AuditController {

    private final SaleService saleService;

    public AuditController(SaleService saleService) {
        this.saleService = saleService;
    }

    @GetMapping("/api/admin/audit")
    public List<Sale> auditLog() {
        return saleService.auditLog();
    }
}
