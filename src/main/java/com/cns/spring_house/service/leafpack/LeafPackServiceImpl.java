package com.cns.spring_house.service.leafpack;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.dto.leafpack.CreateLeafPackRequestDTO;
import com.cns.spring_house.dto.leafpack.LeafPackResponseDTO;
import com.cns.spring_house.exception.BusinessException;
import com.cns.spring_house.exception.EntityAlreadyExistsException;
import com.cns.spring_house.exception.EntityNotFoundException;
import com.cns.spring_house.exception.InvalidLeafConfigurationException;
import com.cns.spring_house.mapper.leafpack.LeafPackMapper;
import com.cns.spring_house.model.leaf.LeafStatus;
import com.cns.spring_house.model.leafpack.LeafPack;
import com.cns.spring_house.model.leafpack.LeafPackStatus;
import com.cns.spring_house.model.stockitem.StockItem;
import com.cns.spring_house.repository.leafpack.LeafPackRepository;
import com.cns.spring_house.repository.stockitem.StockItemRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(rollbackFor = Exception.class)
@RequiredArgsConstructor
public class LeafPackServiceImpl implements LeafPackService {

    private final LeafPackRepository repository;
    private final StockItemRepository stockItemRepository;
    private final LeafPackMapper mapper;

    @Override
    public LeafPackResponseDTO get(UUID id) {
        LeafPack leafPack = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el paquete de resortes solicitado"));
        return mapper.toResponse(leafPack);
    }

    @Override
    public List<LeafPackResponseDTO> getAll() {
        return repository.findAll()
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    public LeafPackResponseDTO create(CreateLeafPackRequestDTO requestDTO) {
        StockItem stockItem = stockItemRepository
                .findById(requestDTO.stockItemId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el stock para esa maleta en ese taller"));

        boolean alreadyExists = repository.existsByProductIdAndWorkshopId(
                stockItem.getProduct().getId(), stockItem.getWorkshop().getId());
        if (alreadyExists) {
            throw new EntityAlreadyExistsException(
                    "Ya existe un stock para esa maleta en ese taller");
        }

        LeafPack leafPack = mapper.toLeafPack(requestDTO);

        leafPack.getLeaves().forEach(leaf -> {
            if (Boolean.TRUE.equals(leaf.getHasHole()) && leaf.getSecondLength() == null) {
                throw InvalidLeafConfigurationException
                        .missingSecondLengthForHoledLeaf(leaf.getLeafNumber());
            }

            if (!Boolean.TRUE.equals(leaf.getHasHole()) && leaf.getSecondLength() != null) {
                throw InvalidLeafConfigurationException
                        .secondLengthNotAllowedWithoutHole(leaf.getLeafNumber());
            }
            leaf.setLeafPack(leafPack);
            leaf.setStatus(LeafStatus.IN_PACKAGE);
        });
        leafPack.setStatus(LeafPackStatus.SEALED);
        leafPack.setOriginalLeafCount(leafPack.getLeaves().size());
        leafPack.setAvailableLeafCount(leafPack.getLeaves().size());
        leafPack.setWorkshop(stockItem.getWorkshop());
        leafPack.setProduct(stockItem.getProduct());

        repository.save(leafPack);

        stockItem.setQuantity(stockItem.getQuantity() + 1);
        stockItemRepository.save(stockItem);

        return mapper.toResponse(leafPack);
    }

    @Override
    public LeafPackResponseDTO open(UUID id) {
        LeafPack leafPack = repository
                .findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el paquete de resortes especificado"));
        if (leafPack.getStatus() != LeafPackStatus.SEALED) {
            throw new BusinessException(
                    "Solo se pueden abrir paquetes de resortes que esten sellados");
        }
        UUID workshopId = leafPack.getWorkshop().getId();
        UUID productId = leafPack.getProduct().getId();
        StockItem stockItem = stockItemRepository
                .findByProductIdAndWorkshopId(
                        productId, workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el stock para ese producto en ese taller"));

        leafPack.getLeaves().forEach(leaf -> {
            leaf.setStatus(LeafStatus.AVAILABLE);
        });
        leafPack.setStatus(LeafPackStatus.OPENED);
        repository.save(leafPack);

        // TODO: What happens if stock item has quantity 0?
        stockItem.setQuantity(stockItem.getQuantity() - 1);
        stockItemRepository.save(stockItem);

        return mapper.toResponse(leafPack);
    }

}
