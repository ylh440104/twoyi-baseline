/* SPDX-License-Identifier: MPL-2.0 */
//! Pure logic core of the minimal guest loader.
//!
//! Stage two of the reconstruction: everything here runs and is tested on the CI
//! host, without a device. The parts that need a real process (mmap, jump to the
//! guest entry, the `B1F2`/`TBD1` handshake, the host libc bridge) are built on
//! top of these modules in a later step.

pub mod broker;
pub mod elf;
pub mod executor;
pub mod image;
pub mod map;
pub mod proto;
pub mod resolver;
pub mod stack;

pub use elf::{Elf, ElfError, Segment};
pub use image::{GuestImage, ImageError, Placed};
pub use executor::{ExecError, MappedImage};
pub use map::{plan, MapError, MappingPlan};
pub use broker::{Broker, ChannelError, HandshakeClient};
pub use proto::{PathError, ProtoError, Tbd1, Tbd1Reply, B1F2};
pub use resolver::{request_once, serve_once, Resolver, ResolverError};
pub use stack::{build as build_stack, Auxv, StackError, StackImage};