# CycleCare Admin design principles

Reusable quality standard (learned from Roy's Lajiwa feedback). It is a standard of judgment, not a layout to copy. Each product keeps its own composition and identity.

1. One focus per screen. The main task owns the space; everything else is one tap away.
2. Hide the machinery. No status chatter, raw ids, JSON or provider jargon in the main view. Detail sits behind "Details".
3. Navigation is a short, grouped map (Members, Money, System), not a long flat list. Every screen stays reachable.
4. Chat is a thread: full height, input pinned and growing, send inside the field, copy on replies, history painted instantly, calm typing indicator.
5. Compact rows beat stacked cards for choices. Status is a dot and a word.
6. Motion explains change (screen in, message in, press feedback), stays under ~260ms, and respects reduced motion.
7. Fast first: no layout jank, no heavy effects on scroll or typing.
8. Mobile first, then widen. Tap targets 40px or more.
