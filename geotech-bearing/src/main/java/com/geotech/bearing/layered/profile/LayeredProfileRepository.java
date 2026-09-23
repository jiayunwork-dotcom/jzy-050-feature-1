package com.geotech.bearing.layered.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 分层剖面头关系型存取。与单层档 {@code SoilProfileRepository} 是两套独立仓储，
 * 多份剖面登记 / 多次核算并发进来时各自操作独立行，互不写串。
 */
public interface LayeredProfileRepository extends JpaRepository<LayeredProfileEntity, Long> {

    Optional<LayeredProfileEntity> findByName(String name);

    boolean existsByName(String name);

    List<LayeredProfileEntity> findAllByOrderByNameAsc();
}
