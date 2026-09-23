package com.geotech.bearing.layeredprofile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 分层剖面的关系型存取。与单层参数档分表存储，两边互不覆盖、互不干扰；
 * 多份剖面登记 / 多次核算并发进来时各自操作独立行，结果互不写串。
 */
public interface LayeredProfileRepository extends JpaRepository<LayeredProfileEntity, Long> {

    @Query("SELECT p FROM LayeredProfileEntity p LEFT JOIN FETCH p.layers WHERE p.name = :name")
    Optional<LayeredProfileEntity> findByNameWithLayers(@Param("name") String name);

    @Query("SELECT DISTINCT p FROM LayeredProfileEntity p LEFT JOIN FETCH p.layers ORDER BY p.name ASC")
    List<LayeredProfileEntity> findAllWithLayersOrderByNameAsc();

    boolean existsByName(String name);
}
