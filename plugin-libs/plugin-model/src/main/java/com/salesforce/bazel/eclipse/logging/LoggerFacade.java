/**
 * Copyright (c) 2019, Salesforce.com, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */
package com.salesforce.bazel.eclipse.logging;

/**
 * Logger facade that can log both to eclipse platform error log and debug log for the bazel plugin.
 * 
 * This is an interface so that tests can use it and verify logging
 * 
 * @author Blaine Buxton
 *
 */
public abstract class LoggerFacade {
    static LoggerFacade instance = new BasicLoggerFacade();

    /**
     * Default instance, this can change - DO NOT CACHE or STORE
     * 
     * @return
     */
    public static LoggerFacade instance() {
        return instance;
    }

    /**
     * log error
     * 
     * @param from
     * @param message
     * @param args
     */
    public abstract void error(Class<?> from, String message, Object... args);

    /**
     * Log error with exception stack trace
     * 
     * @param from
     * @param message
     * @param exception
     * @param args
     */
    public abstract void error(Class<?> from, String message, Throwable exception, Object... args);

    /**
     * Log warn message
     * 
     * @param from
     * @param message
     * @param args
     */
    public abstract void warn(Class<?> from, String message, Object... args);

    /**
     * Log info message
     * 
     * @param from
     * @param message
     * @param args
     */
    public abstract void info(Class<?> from, String message, Object... args);

    /**
     * Log debug message
     * 
     * @param from
     * @param message
     * @param args
     */
    public abstract void debug(Class<?> from, String message, Object... args);

    /**
     * Use only by bazel eclipse plugin activator or tests
     * 
     * @param newFacade
     */
    public static void setInstance(LoggerFacade newFacade) {
        instance = newFacade;
    }

}
