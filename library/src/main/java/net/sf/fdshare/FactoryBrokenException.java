/*
 * Copyright © 2015 Alexander Rvachev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.fdshare;

/**
 * This exception is thrown by factory to indicate, that it is not usable anymore.
 * By the time it was thrown the factory is already closed (but there is no harm from
 * closing it again). Attempting to reuse already closed factory will result in
 * another instance of this exception being thrown.
 */
public final class FactoryBrokenException extends Exception {
    FactoryBrokenException(String reason) {
        super(reason);
    }
}
