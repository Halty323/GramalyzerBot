package com.halty.gramalyzerbot.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByChargeId(String chargeId);

    List<Payment> findTop5ByUserIdOrderByPaidAtDesc(Long userId);

    long countByRefundedAtIsNullAndPaidAtAfter(Instant after);

    @Query("select coalesce(sum(p.stars), 0) from Payment p where p.refundedAt is null and p.paidAt > :after")
    long starsSince(@Param("after") Instant after);
}
