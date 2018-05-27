/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.binding.openwebnet.handler;

import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.*;

import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StopMoveType;
import org.eclipse.smarthome.core.library.types.UpDownType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.openwebnet.OpenWebNetBindingConstants;
import org.openwebnet.message.Automation;
import org.openwebnet.message.BaseOpenMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link OpenWebNetAutomationHandler} is responsible for handling commands/messages for a Automation OpenWebNet
 * device.
 * It extends the abstract {@link OpenWebNetThingHandler}.
 *
 * @author Massimo Valla - Initial contribution
 */
public class OpenWebNetAutomationHandler extends OpenWebNetThingHandler {

    private final Logger logger = LoggerFactory.getLogger(OpenWebNetAutomationHandler.class);

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = OpenWebNetBindingConstants.AUTOMATION_SUPPORTED_THING_TYPES;

    protected Automation.Type automationType = Automation.Type.ZIGBEE;

    private ChannelUID channel;

    // internal states
    public static final int STATE_STOPPED = 0;
    public static final int STATE_MOVING_UP = 1;
    public static final int STATE_MOVING_DOWN = 2;
    public static final int STATE_UNKNOWN = -1;

    // calibration states
    public static final int CALIBRATION_INACTIVE = -1;
    public static final int CALIBRATION_ACTIVATED = 0;
    public static final int CALIBRATION_GOING_UP = 1;
    public static final int CALIBRATION_GOING_DOWN = 2;

    // positions
    public static final int POSITION_DOWN = 0;
    public static final int POSITION_UP = 100;
    public static final int POSITION_UNKNOWN = -1;
    public static final int SHUTTER_RUN_UNDEFINED = -1;
    // private static final int SHUTTER_RUN_MIN = 1000; // ms

    private int shutterRun = SHUTTER_RUN_UNDEFINED;

    private long startedMovingAt = SHUTTER_RUN_UNDEFINED;
    private int internalState = STATE_UNKNOWN;
    private int positionEst = POSITION_UNKNOWN;
    private ScheduledFuture<?> moveSchedule;
    // private boolean goToPositionRequested = false;
    private int positionRequested = POSITION_UNKNOWN;
    private int calibrating = CALIBRATION_INACTIVE;
    private static final int STEP_TIME_MIN = 50; // ms

    public OpenWebNetAutomationHandler(@NonNull Thing thing) {
        super(thing);
        logger.debug("==OWN:AutomationHandler== constructor");
    }

    @Override
    public void initialize() {
        super.initialize();
        logger.debug("==OWN:AutomationHandler== initialize() thing={}", thing.getUID());
        // TODO change to device where
        Channel ch = thing.getChannel(CHANNEL_SHUTTER);
        if (ch != null) {
            channel = ch.getUID();
        } else {
            logger.warn("==OWN:AutomationHandler== Cannot get channel in initialize()");
            return;
        }
        if (bridgeHandler != null && bridgeHandler.isBusGateway()) {
            automationType = Automation.Type.POINT_TO_POINT;
        }
        Object shutterRunConfig = getConfig().get(CONFIG_PROPERTY_SHUTTER_RUN);
        try {
            if (shutterRunConfig == null) {
                shutterRunConfig = new String("AUTO");
                logger.debug("==OWN:AutomationHandler== shutterRun null, default to AUTO");
            } else if (shutterRunConfig instanceof java.lang.String) {
                if ("AUTO".equals(((String) shutterRunConfig).toUpperCase())) {
                    logger.debug("==OWN:AutomationHandler== shutterRun set to AUTO in configuration");
                    shutterRun = SHUTTER_RUN_UNDEFINED;
                } else { // try to parse int>=1000
                    int shutterRunInt = Integer.parseInt((String) shutterRunConfig);
                    if (shutterRunInt < 1000) {
                        throw new NumberFormatException();
                    }
                    shutterRun = shutterRunInt;
                    logger.debug("==OWN:AutomationHandler== shutterRun set to {}", shutterRun);
                }

            } else {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            logger.warn("==OWN:AutomationHandler== Wrong configuration: {} must be AUTO or an integer >= 1000",
                    CONFIG_PROPERTY_SHUTTER_RUN);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "@text/offline.wrong-configuration");
            shutterRun = SHUTTER_RUN_UNDEFINED;
        }
        updateState(CHANNEL_SHUTTER, UnDefType.UNDEF);
        positionEst = POSITION_UNKNOWN;
    }

    @Override
    protected void requestChannelState(ChannelUID channel) {
        logger.debug("==OWN:AutomationHandler== requestChannelState() thingUID={} channel={}", thing.getUID(),
                channel.getId());
        gateway.send(Automation.requestStatus(toWhere(channel), automationType));
    }

    @Override
    protected void handleChannelCommand(ChannelUID channel, Command command) {
        switch (channel.getId()) {
            case CHANNEL_SHUTTER:
                handleShutterCommand(channel, command);
                break;
            default: {
                logger.warn("==OWN:AutomationHandler== Unsupported channel UID {}", channel);
            }
        }
        // TODO
        // Note: if communication with thing fails for some reason,
        // indicate that by setting the status with detail information
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
        // "Could not control device at IP address x.x.x.x");
    }

    /**
     * Handles Automation Rollershutter command (UP/DOWN, STOP/MOVE, PERCENT xx%)
     *
     * @param command
     */
    private void handleShutterCommand(ChannelUID channel, Command command) {
        logger.debug("==OWN:AutomationHandler== handleShutterCommand() (command={})", command);
        calibrating = CALIBRATION_INACTIVE;
        if (command instanceof UpDownType) {
            if (UpDownType.UP.equals(command)) {
                gateway.send(Automation.requestMoveUp(toWhere(channel), automationType));
            } else { // DOWN
                gateway.send(Automation.requestMoveDown(toWhere(channel), automationType));
            }
        } else if (StopMoveType.STOP.equals(command)) {
            gateway.send(Automation.requestStop(toWhere(channel), automationType));
        } else if (command instanceof PercentType) {
            if (internalState != STATE_STOPPED) {
                logger.debug("==OWN:AutomationHandler== already moving, ignoring go-to-XX%");
                return;
            }
            int percent = ((PercentType) command).intValue();
            if (percent != positionEst) {
                if (percent == POSITION_DOWN) { // all down
                    gateway.send(Automation.requestMoveDown(toWhere(channel), automationType));
                } else if (percent == POSITION_UP) { // all up
                    gateway.send(Automation.requestMoveUp(toWhere(channel), automationType));
                } else {// go to XX%
                    logger.debug("==OWN:AutomationHandler== {}% requested", percent);
                    if (shutterRun == SHUTTER_RUN_UNDEFINED) {
                        logger.debug("==OWN:AutomationHandler== && shutterRun not configured, starting CALIBRATION...");
                        calibrating = CALIBRATION_ACTIVATED;
                        gateway.send(Automation.requestMoveUp(toWhere(channel), automationType));
                        positionRequested = percent;
                    } else if (shutterRun > 0 && positionEst != POSITION_UNKNOWN) { // these must be known to calculate
                                                                                    // moveTime
                        // calculate how much time we have to move and set a deadline to stop after that time
                        int moveTime = Math
                                .round(((float) Math.abs(percent - positionEst) / POSITION_DOWN * shutterRun));
                        logger.debug("==OWN:AutomationHandler== moveTime={}", moveTime);
                        if (moveTime > STEP_TIME_MIN) { // FIXME calibrate this
                            if (moveSchedule != null && !moveSchedule.isDone()) {
                                // a moveSchedule was already scheduled and is not done... let's cancel the schedule
                                moveSchedule.cancel(false);
                                logger.warn( // should not get here....
                                        "==OWN:AutomationHandler== new XX% requested, old moveSchedule cancelled");
                            }
                            // start the schedule BEFORE sending the command, because the synch command waits for ACK
                            // and can take some 300ms
                            moveSchedule = scheduler.schedule(() -> {
                                logger.debug("==OWN:AutomationHandler== moveSchedule expired, sending STOP...");
                                gateway.send(Automation.requestStop(toWhere(channel), automationType));
                            }, moveTime, TimeUnit.MILLISECONDS);
                            if (percent < positionEst) {
                                gateway.send(Automation.requestMoveUp(toWhere(channel), automationType));
                            } else {
                                gateway.send(Automation.requestMoveDown(toWhere(channel), automationType));
                            }
                            logger.debug("==OWN:AutomationHandler== ...sending returned");
                        } else {
                            logger.warn("==OWN:AutomationHandler== moveTime < MIN_STEP_TIME, do nothing");
                        }
                    } else {
                        logger.warn(
                                "==OWN:AutomationHandler== Command {} cannot be executed: unknown position or shutterRun configuration param not set (thing={})",
                                command, thing.getUID());
                    }
                }
            } else {
                logger.debug(
                        "==OWN:AutomationHandler== handleShutterCommand() Command {}% == positionEst, nothing to do",
                        percent);
            }
        } else {
            logger.warn("==OWN:AutomationHandler== Command {} is not supported for thing {}", command, thing.getUID());
        }
    }

    @Override
    protected void handleMessage(BaseOpenMessage msg) {
        super.handleMessage(msg);
        updateAutomationState((Automation) msg);
    }

    /**
     * Updates automation device state based on an Automation message received from the OWN network
     */
    private void updateAutomationState(Automation msg) {
        if (msg.isUp()) {
            updateStateInt(STATE_MOVING_UP);
            if (calibrating == CALIBRATION_ACTIVATED) {
                calibrating = CALIBRATION_GOING_UP;
                logger.debug("==OWN:AutomationHandler== && started going ALL UP...");
            }
        } else if (msg.isDown()) {
            updateStateInt(STATE_MOVING_DOWN);
            if (calibrating == CALIBRATION_ACTIVATED) {
                calibrating = CALIBRATION_GOING_DOWN;
                logger.debug("==OWN:AutomationHandler== && started going ALL DOWN...");
            }
        } else if (msg.isStop()) {
            long stoppedAt = System.currentTimeMillis();
            if (calibrating == CALIBRATION_GOING_DOWN && shutterRun == SHUTTER_RUN_UNDEFINED) {
                shutterRun = (int) (stoppedAt - startedMovingAt);
                logger.debug("==OWN:AutomationHandler== && reached DOWN ===> shutterRun={}", shutterRun);
                updateStateInt(STATE_STOPPED);
                logger.debug("==OWN:AutomationHandler== && -- CALIBRATION COMPLETED--, now going to percent {}",
                        positionRequested);
                handleShutterCommand(channel, new PercentType(positionRequested));
                Configuration configuration = editConfiguration();
                configuration.put(CONFIG_PROPERTY_SHUTTER_RUN, Integer.toString(shutterRun));
                updateConfiguration(configuration);
            } else if (calibrating == CALIBRATION_GOING_UP) {
                updateStateInt(STATE_STOPPED);
                logger.debug("==OWN:AutomationHandler== && reached UP, now sending DOWN command...", shutterRun);
                calibrating = CALIBRATION_ACTIVATED;
                gateway.send(Automation.requestMoveDown(toWhere(channel), automationType));
            } else {
                updateStateInt(STATE_STOPPED);
            }
        } else {
            logger.warn(
                    "==OWN:AutomationHandler== updateAutomationState() FRAME {} NOT SUPPORTED for thing {}, ignoring it.",
                    msg, thing.getUID());
        }
    }

    /** Updates internal state: state and positionEst */
    private void updateStateInt(int newState) {
        if (internalState == STATE_STOPPED) {
            if (newState != STATE_STOPPED) { // move after stop
                startedMovingAt = System.currentTimeMillis();
                logger.debug("==OWN:AutomationHandler== startedMovingAt={}", startedMovingAt);
            }
        } else { // we were moving
            updatePosition();
            if (newState != STATE_STOPPED) { // move after move, take new timestamp
                startedMovingAt = System.currentTimeMillis();
                logger.debug("==OWN:AutomationHandler== startedMovingAt={}", startedMovingAt);
            }
            // cancel the schedule
            if (moveSchedule != null && !moveSchedule.isDone()) {
                moveSchedule.cancel(false);
            }
        }
        internalState = newState;
        logger.debug("==OWN:AutomationHandler== [[[ internalState={} positionEst={} - calibrating={} shutterRun={} ]]]",
                internalState, positionEst, calibrating, shutterRun);
    }

    /**
     * update positionEst based on movement time and current internalState
     *
     */
    private void updatePosition() {
        int newPos = POSITION_UNKNOWN;
        if (shutterRun > 0) {// we have shutterRun defined, let's calculate new positionEst
            long movementTime = System.currentTimeMillis() - startedMovingAt;
            logger.trace("==OWN:AutomationHandler== positionEst={}", positionEst);
            logger.trace("==OWN:AutomationHandler== runDelta={}", movementTime);
            int movementSteps = Math.round((float) movementTime / shutterRun * POSITION_DOWN);
            logger.debug("==OWN:AutomationHandler== movementSteps={} {}", movementSteps,
                    (internalState == STATE_MOVING_DOWN) ? "DOWN(+)" : "UP(-)");
            if (positionEst == POSITION_UNKNOWN && movementSteps >= POSITION_DOWN) { // we did a full run
                newPos = (internalState == STATE_MOVING_DOWN) ? POSITION_DOWN : POSITION_UP;
            } else if (positionEst != POSITION_UNKNOWN) {
                newPos = positionEst + ((internalState == STATE_MOVING_DOWN) ? movementSteps : -movementSteps);
                logger.trace("==OWN:AutomationHandler== {} {} {} = {}", positionEst,
                        (internalState == STATE_MOVING_DOWN) ? "+" : "-", movementSteps, newPos);
                if (newPos > POSITION_DOWN) {
                    newPos = POSITION_DOWN;
                }
                if (newPos < POSITION_UP) {
                    newPos = POSITION_UP;
                }
            }
        }
        if (newPos != POSITION_UNKNOWN) {
            if (newPos != positionEst) {
                updateState(CHANNEL_SHUTTER, new PercentType(newPos));
            }
        } else {
            updateState(CHANNEL_SHUTTER, UnDefType.UNDEF);
        }
        positionEst = newPos;
    }

    @Override
    public void thingUpdated(Thing thing) {
        super.thingUpdated(thing);
        logger.debug("==OWN:AutomationHandler== thingUpdated()");
    }
} /* class */
