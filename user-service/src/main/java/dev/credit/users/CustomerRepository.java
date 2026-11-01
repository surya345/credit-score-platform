package dev.credit.users;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, String> {
  Page<Customer> findByFullNameContainingIgnoreCaseOrReferenceContainingIgnoreCase(
      String name, String ref, Pageable p);
}
