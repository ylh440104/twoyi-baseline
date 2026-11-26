/* SPDX-License-Identifier: MPL-2.0 */
//! Pure logic core of the minimal guest loader.
//!
//! Stage two of the reconstruction: everything here runs and is tested on the CI
//! host, without a device. The parts that need a real process (mmap, jump to the
//! guest entry, the `B1F2`/`TBD1` handshake, the host libc bridge) are built on
//! top of these modules in a later step.

pub mod broker;
pub mod elf;
pub mod map;
pub mod proto;
pub mod stack;

pub use elf::{Elf, ElfError, Segment};
pub use map::{plan, MapError, MappingPlan};
pub use broker::{Broker, ChannelError, HandshakeClient};
pub use proto::{ProtoError, Tbd1, B1F2};
pub use stack::{build as build_stack, Auxv, StackError, StackImage};