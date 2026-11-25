/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.twoyi;

/** Tracks a window lease by identity; callbacks are serialized on the UI thread. */
final class WindowOwner {
    private Object owner;

    void attach(Object token) {
        if (token == null) throw new IllegalArgumentException("window token is null");
        owner = token;
    }

    boolean isCurrent(Object token) {
        return token != null && owner == token;
    }

    boolean detach(Object token) {
        if (!isCurrent(token)) return false;
        owner = null;
        return true;
    }
}
