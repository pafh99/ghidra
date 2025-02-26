/* ###
 * IP: GHIDRA
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
package ghidra.app.plugin.core.debug.service.emulation;

import java.util.concurrent.*;

import ghidra.app.plugin.core.debug.service.emulation.data.PcodeDebuggerDataAccess;
import ghidra.pcode.exec.AccessPcodeExecutionException;
import ghidra.pcode.exec.trace.BytesTracePcodeExecutorStatePiece;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.Language;
import ghidra.trace.model.memory.TraceMemoryState;

/**
 * An abstract executor state piece that knows to read live state if applicable
 *
 * <p>
 * This requires a memory-access shim for the debugger. It will check if the shim is associated with
 * a live session. If so, it will direct the recorder to capture the desired state, if they're not
 * already {@link TraceMemoryState#KNOWN}. When such a target comments is required, the state will
 * wait up to 1 second for it to complete (see
 * {@link AbstractRWTargetCachedSpace#waitTimeout(CompletableFuture)}).
 */
public abstract class AbstractRWTargetPcodeExecutorStatePiece
		extends BytesTracePcodeExecutorStatePiece {

	abstract class AbstractRWTargetCachedSpace extends CachedSpace {

		public AbstractRWTargetCachedSpace(Language language, AddressSpace space,
				PcodeDebuggerDataAccess backing) {
			super(language, space, backing);
		}

		protected abstract void fillUninitialized(AddressSet uninitialized);

		@Override
		public byte[] read(long offset, int size) {
			if (backing != null) {
				AddressSet uninitialized =
					addrSet(bytes.getUninitialized(offset, offset + size - 1));
				if (uninitialized.isEmpty()) {
					return super.read(offset, size);
				}

				fillUninitialized(uninitialized);

				AddressSetView unknown = backing.intersectUnknown(
					addrSet(bytes.getUninitialized(offset, offset + size - 1)));
				if (!unknown.isEmpty()) {
					warnUnknown(unknown);
				}
			}

			// TODO: What to flush when bytes in the trace change?
			return super.read(offset, size);
		}

		protected <T> T waitTimeout(CompletableFuture<T> future) {
			try {
				return future.get(1, TimeUnit.SECONDS);
			}
			catch (TimeoutException e) {
				throw new AccessPcodeExecutionException("Timed out reading or writing target", e);
			}
			catch (InterruptedException | ExecutionException e) {
				throw new AccessPcodeExecutionException("Error reading or writing target", e);
			}
		}
	}

	protected final PcodeDebuggerDataAccess data;

	/**
	 * Construct a piece
	 * 
	 * @param data the trace-data access shim
	 */
	public AbstractRWTargetPcodeExecutorStatePiece(PcodeDebuggerDataAccess data) {
		super(data);
		this.data = data;
	}

	/**
	 * A partially implemented space map which retrieves "backing" objects from the trace's memory
	 * and register spaces.
	 */
	protected abstract class TargetBackedSpaceMap
			extends CacheingSpaceMap<PcodeDebuggerDataAccess, CachedSpace> {
		@Override
		protected PcodeDebuggerDataAccess getBacking(AddressSpace space) {
			return data;
		}
	}
}
