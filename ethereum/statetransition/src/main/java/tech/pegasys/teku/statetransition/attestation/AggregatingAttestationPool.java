/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.statetransition.attestation;

import static tech.pegasys.teku.util.config.Constants.ATTESTATION_RETENTION_EPOCHS;
import static tech.pegasys.teku.util.config.Constants.SLOTS_PER_EPOCH;

import com.google.common.primitives.UnsignedLong;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.datastructures.blocks.BeaconBlockBodyLists;
import tech.pegasys.teku.datastructures.operations.Attestation;
import tech.pegasys.teku.datastructures.operations.AttestationData;
import tech.pegasys.teku.ssz.SSZTypes.SSZList;
import tech.pegasys.teku.ssz.SSZTypes.SSZMutableList;
import tech.pegasys.teku.util.time.channels.SlotEventsChannel;

/**
 * Maintains a pool of attestations. Attestations can be retrieved either for inclusion in a block
 * or as an aggregate to publish as part of the naive attestation aggregation algorithm. In both
 * cases the returned attestations are aggregated to maximise the number of validators that can be
 * included.
 */
public class AggregatingAttestationPool implements SlotEventsChannel {

  private final Map<Bytes, MatchingDataAttestationGroup> attestationGroupByDataHash =
      new HashMap<>();
  private final NavigableMap<UnsignedLong, Set<Bytes>> dataHashBySlot = new TreeMap<>();

  public synchronized void add(final Attestation attestation) {
    final Bytes32 dataHash = attestation.getData().hash_tree_root();
    attestationGroupByDataHash
        .computeIfAbsent(dataHash, key -> new MatchingDataAttestationGroup(attestation.getData()))
        .add(attestation);

    dataHashBySlot
        .computeIfAbsent(attestation.getData().getSlot(), slot -> new HashSet<>())
        .add(dataHash);
  }

  @Override
  public synchronized void onSlot(final UnsignedLong slot) {
    final UnsignedLong attestationRetentionSlots =
        UnsignedLong.valueOf(SLOTS_PER_EPOCH * ATTESTATION_RETENTION_EPOCHS);
    if (slot.compareTo(attestationRetentionSlots) <= 0) {
      return;
    }
    final UnsignedLong firstValidAttestationSlot = slot.minus(attestationRetentionSlots);
    final Collection<Set<Bytes>> dataHashesToRemove =
        dataHashBySlot.headMap(firstValidAttestationSlot, false).values();
    dataHashesToRemove.stream().flatMap(Set::stream).forEach(attestationGroupByDataHash::remove);
    dataHashesToRemove.clear();
  }

  public synchronized void remove(final Attestation attestation) {
    final Bytes32 dataRoot = attestation.getData().hash_tree_root();
    final MatchingDataAttestationGroup attestations = attestationGroupByDataHash.get(dataRoot);
    if (attestations == null) {
      return;
    }
    attestations.remove(attestation);
    if (attestations.isEmpty()) {
      attestationGroupByDataHash.remove(dataRoot);
      removeFromSlotMappings(attestation.getData().getSlot(), dataRoot);
    }
  }

  private void removeFromSlotMappings(final UnsignedLong slot, final Bytes32 dataRoot) {
    final Set<Bytes> dataHashesForSlot = dataHashBySlot.get(slot);
    if (dataHashesForSlot != null) {
      dataHashesForSlot.remove(dataRoot);
      if (dataHashesForSlot.isEmpty()) {
        dataHashBySlot.remove(slot);
      }
    }
  }

  public synchronized SSZList<Attestation> getAttestationsForBlock(final UnsignedLong slot) {
    final SSZMutableList<Attestation> attestations = BeaconBlockBodyLists.createAttestations();
    attestationGroupByDataHash.values().stream()
        .filter(group -> group.getAttestationData().canIncludeInBlockAtSlot(slot))
        .flatMap(MatchingDataAttestationGroup::stream)
        .limit(attestations.getMaxSize())
        .forEach(attestations::add);
    return attestations;
  }

  public synchronized Optional<Attestation> createAggregateFor(
      final AttestationData attestationData) {
    return Optional.ofNullable(attestationGroupByDataHash.get(attestationData.hash_tree_root()))
        .flatMap(attestations -> attestations.stream().findFirst());
  }
}
