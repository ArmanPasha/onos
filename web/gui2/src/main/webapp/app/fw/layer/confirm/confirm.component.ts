/*
 * Copyright 2018-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Component, Input, Output, EventEmitter } from '@angular/core';
import { trigger, state, style, animate, transition } from '@angular/animations';
import { LogService } from '../../../log.service';

/**
 * ONOS GUI -- Layer -- Confirm Component
 *
 * Replaces Flash Service in old GUI.
 * Provides a mechanism to present a confirm dialog to the screen
 *
 * To use add an element to the template like
 *   <onos-confirm message="Performing something dangerous. Would you like to proceed"></onos-flash>
 *
 * An event is raised with either OK or Cancel
 */
@Component({
    selector: 'onos-confirm',
    templateUrl: './confirm.component.html',
    styleUrls: [
        './confirm.component.css',
        './confirm.theme.css',
        '../dialog.css',
        '../dialog.theme.css',
        '../../widget/panel.css',
        '../../widget/panel-theme.css'
    ],
    animations: [
        trigger('confirmDlgState', [
            state('true', style({
                transform: 'translateX(-100%)',
                opacity: '100'
            })),
            state('false', style({
                transform: 'translateX(0%)',
                opacity: '0'
            })),
            transition('0 => 1', animate('100ms ease-in')),
            transition('1 => 0', animate('100ms ease-out'))
        ])
    ]
})
export class ConfirmComponent {
    @Input() message: string;
    @Input() warning: string;
    @Output() chosen: EventEmitter<boolean> = new EventEmitter();

    constructor(
        private log: LogService,
    ) {
        this.log.debug('ConfirmComponent constructed');
    }

    /**
     * When OK or Cancel is pressed, send an event to parent with choice
     */
    choice(chosen: boolean): void {
        this.chosen.emit(chosen);
    }
}
