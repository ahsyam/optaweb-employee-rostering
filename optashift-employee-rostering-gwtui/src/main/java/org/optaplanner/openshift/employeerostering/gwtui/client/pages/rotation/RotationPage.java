/*
 * Copyright (C) 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.openshift.employeerostering.gwtui.client.pages.rotation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import javax.enterprise.event.Observes;
import javax.inject.Inject;

import elemental2.dom.HTMLButtonElement;
import elemental2.dom.MouseEvent;
import elemental2.promise.Promise;
import org.jboss.errai.ui.shared.api.annotations.DataField;
import org.jboss.errai.ui.shared.api.annotations.EventHandler;
import org.jboss.errai.ui.shared.api.annotations.ForEvent;
import org.jboss.errai.ui.shared.api.annotations.Templated;
import org.optaplanner.openshift.employeerostering.gwtui.client.pages.Page;
import org.optaplanner.openshift.employeerostering.gwtui.client.rostergrid.model.Viewport;
import org.optaplanner.openshift.employeerostering.gwtui.client.rostergrid.view.ViewportView;
import org.optaplanner.openshift.employeerostering.gwtui.client.tenant.TenantStore;
import org.optaplanner.openshift.employeerostering.shared.common.AbstractPersistable;
import org.optaplanner.openshift.employeerostering.shared.lang.tokens.IdOrGroup;
import org.optaplanner.openshift.employeerostering.shared.lang.tokens.ShiftInfo;
import org.optaplanner.openshift.employeerostering.shared.lang.tokens.ShiftTemplate;
import org.optaplanner.openshift.employeerostering.shared.shift.Shift;
import org.optaplanner.openshift.employeerostering.shared.shift.ShiftRestServiceBuilder;
import org.optaplanner.openshift.employeerostering.shared.spot.Spot;
import org.optaplanner.openshift.employeerostering.shared.spot.SpotGroup;
import org.optaplanner.openshift.employeerostering.shared.spot.SpotRestServiceBuilder;
import org.optaplanner.openshift.employeerostering.shared.timeslot.TimeSlot;

import static java.util.Collections.singletonList;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.optaplanner.openshift.employeerostering.gwtui.client.common.FailureShownRestCallback.onSuccess;
import static org.optaplanner.openshift.employeerostering.gwtui.client.util.PromiseUtils.resolve;

@Templated
public class RotationPage implements Page {

    @Inject
    @DataField("viewport")
    private ViewportView<Long> viewportView;

    @Inject
    @DataField("configuration")
    private RotationConfigurationView rotationsConfigurationView;

    @Inject
    @DataField("save-button")
    private HTMLButtonElement saveButton;

    @Inject
    private TenantStore tenantStore;

    @Inject
    private RotationViewportFactory rotationViewportFactory;

    private Map<Long, Spot> spotsById;
    private Map<Long, SpotGroup> spotGroupsById;
    private Viewport<Long> viewport;

    @Override
    public Promise<Void> beforeOpen() {
        return refresh();
    }

    public void onTenantChanged(final @Observes TenantStore.TenantChange tenant) {
        refresh();
    }

    public Promise<Void> refresh() {
        return fetchSpots()
                .then(spots -> {
                    spotsById = spots.stream().collect(toMap(AbstractPersistable::getId, identity()));
                    return fetchSpotGroups();
                })
                .then(spotGroups -> {
                    spotGroupsById = spotGroups.stream().collect(toMap(AbstractPersistable::getId, identity()));
                    return fetchShiftTemplate();
                })
                .then(shiftTemplate -> {

                    final Map<Spot, List<Shift>> shiftsBySpot = buildShiftList(shiftTemplate).stream()
                            .collect(groupingBy(Shift::getSpot));

                    viewport = rotationViewportFactory.getViewport(shiftsBySpot);
                    viewportView.setViewport(viewport);
                    return resolve();
                });
    }

    // Method reference does not work, do not change
    private Promise<List<Spot>> fetchSpots() {
        return new Promise<>((resolve, reject) -> {
            SpotRestServiceBuilder.getSpotList(tenantStore.getCurrentTenantId(),
                                               onSuccess(value -> resolve.onInvoke(value)));
        });
    }

    // Method reference does not work, do not change
    private Promise<List<SpotGroup>> fetchSpotGroups() {
        return new Promise<>((resolve, reject) -> {
            SpotRestServiceBuilder.getSpotGroups(tenantStore.getCurrentTenantId(),
                                                 onSuccess(value -> resolve.onInvoke(value)));
        });
    }

    // Method reference does not work, do not change
    private Promise<ShiftTemplate> fetchShiftTemplate() {
        return new Promise<>((resolve, reject) -> {
            ShiftRestServiceBuilder.getTemplate(tenantStore.getCurrentTenantId(),
                                                onSuccess(value -> resolve.onInvoke(value)));
        });
    }

    private List<Shift> buildShiftList(final ShiftTemplate shiftTemplate) {
        final AtomicLong id = new AtomicLong(0L);
        return shiftTemplate.getShiftList().stream()
                .flatMap(shiftInfo -> getShiftStream(shiftInfo, id.getAndIncrement()))
                .collect(toList());
    }

    private Stream<Shift> getShiftStream(final ShiftInfo shiftInfo,
                                         final Long id) {

        return shiftInfo.getSpotList().stream()
                .flatMap(this::getSpots)
                .map(spot -> newShift(id, shiftInfo, spot));
    }

    private Stream<Spot> getSpots(final IdOrGroup spotIdOrGroup) {
        if (spotIdOrGroup.getIsGroup()) {
            return spotGroupsById.get(spotIdOrGroup.getItemId()).getSpots().stream();
        } else {
            return Stream.of(spotsById.get(spotIdOrGroup.getItemId()));
        }
    }

    private Shift newShift(final Long id,
                           final ShiftInfo shift,
                           final Spot spot) {

        final TimeSlot newTimeSlot = new TimeSlot(
                tenantStore.getCurrentTenantId(),
                shift.getStartTime(),
                shift.getEndTime());

        final Shift newShift = new Shift(
                tenantStore.getCurrentTenantId(),
                spot,
                newTimeSlot);

        newShift.setId(id);

        return newShift;
    }

    @EventHandler("save-button")
    private void onSaveClicked(final @ForEvent("click") MouseEvent e) {
        save();
        e.preventDefault();
    }

    private void save() {

        final List<ShiftInfo> newShiftInfoList = viewport.getLanes().stream()
                .flatMap(lane -> lane.getSubLanes().stream())
                .flatMap(subLane -> subLane.getBlobs().stream())
                .filter(blob -> blob.getPositionInGridPixels() >= 0) //Removes left-most twins
                .map(blob -> ((ShiftBlob) blob).getShift())
                .map(this::newShiftInfo)
                .collect(toList());

        ShiftRestServiceBuilder.createTemplate(
                tenantStore.getCurrentTenantId(),
                newShiftInfoList,
                onSuccess(i -> refresh()));
    }

    private ShiftInfo newShiftInfo(final Shift shift) {
        return new ShiftInfo(tenantStore.getCurrentTenantId(),
                             shift.getTimeSlot().getStartDateTime(),
                             shift.getTimeSlot().getEndDateTime(),
                             singletonList(getGroupOrSpotId(shift.getSpot())),
                             new ArrayList<>());
    }

    private IdOrGroup getGroupOrSpotId(final Spot s) {

        //Try searching for a SpotGroup first
        return spotGroupsById.values().stream()
                .filter(group -> group.hasSpot(s)).findAny()
                .map(group -> new IdOrGroup(tenantStore.getCurrentTenantId(), true, group.getId()))

                //If it's not a SpotGroup we search for a Spot
                .orElseGet(() -> Optional.ofNullable(spotsById.get(s.getId()))
                        .map(spot -> new IdOrGroup(tenantStore.getCurrentTenantId(), false, spot.getId()))
                        .orElseThrow(() -> new RuntimeException("Spot should be present either as a group or standalone")));
    }
}