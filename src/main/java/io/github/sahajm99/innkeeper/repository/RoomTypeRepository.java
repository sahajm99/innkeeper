package io.github.sahajm99.innkeeper.repository;

import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.model.RoomType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    Optional<RoomType> findByCode(String code);

    List<RoomType> findAllByOrderBySortOrder();
}
