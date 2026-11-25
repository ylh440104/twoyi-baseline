// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

/// Android pointer IDs are not evdev slot indices and need not be consecutive.
pub struct TouchSlots<const N: usize> {
    ids: [Option<i32>; N],
}

impl<const N: usize> TouchSlots<N> {
    pub const fn new() -> Self { Self { ids: [None; N] } }

    pub fn find(&self, id: i32) -> Option<usize> {
        self.ids.iter().position(|entry| *entry == Some(id))
    }

    pub fn press(&mut self, id: i32) -> Option<usize> {
        if id < 0 { return None; }
        if let Some(slot) = self.find(id) { return Some(slot); }
        let slot = self.ids.iter().position(Option::is_none)?;
        self.ids[slot] = Some(id);
        Some(slot)
    }

    pub fn release(&mut self, id: i32) -> Option<usize> {
        let slot = self.find(id)?;
        self.ids[slot] = None;
        Some(slot)
    }

    pub fn clear(&mut self) -> [Option<i32>; N] {
        std::mem::replace(&mut self.ids, [None; N])
    }

    pub fn is_empty(&self) -> bool { self.ids.iter().all(Option::is_none) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sparse_pointer_ids_use_bounded_slots() {
        let mut slots = TouchSlots::<2>::new();
        assert_eq!(slots.press(31), Some(0));
        assert_eq!(slots.press(7), Some(1));
        assert_eq!(slots.find(31), Some(0));
        assert_eq!(slots.press(31), Some(0));
        assert_eq!(slots.press(9), None);
        assert_eq!(slots.release(31), Some(0));
        assert_eq!(slots.press(9), Some(0));
        assert_eq!(slots.find(7), Some(1));
    }

    #[test]
    fn cancel_releases_all_and_allows_new_gesture() {
        let mut slots = TouchSlots::<5>::new();
        slots.press(4); slots.press(19);
        assert_eq!(slots.clear(), [Some(4), Some(19), None, None, None]);
        assert!(slots.is_empty());
        assert_eq!(slots.release(19), None);
        assert_eq!(slots.press(-1), None);
        assert_eq!(slots.press(30), Some(0));
    }
}
