package com.vendorshield.vendor;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vendors")
@Transactional(readOnly = true)
public class VendorController {

    private final VendorRepository vendors;

    public VendorController(VendorRepository vendors) {
        this.vendors = vendors;
    }

    @GetMapping
    public List<Vendor> list(@RequestParam(defaultValue = "false") boolean directOnly) {
        return directOnly ? vendors.findByParentIsNull() : vendors.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Vendor> get(@PathVariable Long id) {
        return ResponseEntity.of(vendors.findById(id));
    }

    /** Fourth parties: subcontractors sitting behind this vendor. */
    @GetMapping("/{id}/subcontractors")
    public List<Vendor> subcontractors(@PathVariable Long id) {
        return vendors.findByParentId(id);
    }
}
