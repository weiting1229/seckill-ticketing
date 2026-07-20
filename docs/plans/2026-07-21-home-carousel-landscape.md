# Home Carousel and Landscape Event Cards Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add admin-curated homepage event carousel ordering and convert homepage event imagery to uncropped 3:2 landscape presentation.

**Architecture:** Extend the existing event aggregate and MyBatis mappings with two carousel fields, expose a small public featured-events query, and keep carousel rendering in a focused Vue component. The homepage fetches featured and paginated event data independently so a carousel failure never blocks event discovery.

**Tech Stack:** Java 25, Spring Boot 4, MyBatis, Flyway, PostgreSQL, Vue 3, TypeScript, Element Plus, Vitest.

---

### Task 1: Persist carousel curation fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__add_event_featured_fields.sql`
- Modify: `backend/src/main/java/com/seckill/event/domain/Event.java`
- Modify: `backend/src/main/resources/mapper/EventMapper.xml`
- Test: `backend/src/test/java/com/seckill/event/AdminEventFlowIT.java`

1. Add a failing admin integration assertion for `featured` and `featuredOrder`.
2. Run `./mvnw -Dtest=AdminEventFlowIT test` and confirm the new assertion fails.
3. Add the Flyway columns, domain fields, insert/update mappings and selected columns.
4. Run the focused test and confirm persistence passes.
5. Commit with `feat(backend): persist homepage carousel settings`.

### Task 2: Extend admin event contracts

**Files:**
- Modify: `backend/src/main/java/com/seckill/event/dto/CreateEventRequest.java`
- Modify: `backend/src/main/java/com/seckill/event/dto/UpdateEventRequest.java`
- Modify: `backend/src/main/java/com/seckill/event/dto/EventAdminResponse.java`
- Modify: `backend/src/main/java/com/seckill/event/service/EventService.java`
- Test: `backend/src/test/java/com/seckill/event/AdminEventFlowIT.java`

1. Write failing create/update response tests for carousel settings and invalid negative order.
2. Verify the focused tests fail for missing contract fields/validation.
3. Add `featured` and non-negative nullable `featuredOrder`; normalize disabled events to a null order.
4. Run the focused tests and full backend test suite.
5. Commit with `feat(backend): expose carousel settings to admins`.

### Task 3: Add public featured-events query

**Files:**
- Modify: `backend/src/main/java/com/seckill/event/mapper/EventMapper.java`
- Modify: `backend/src/main/resources/mapper/EventMapper.xml`
- Modify: `backend/src/main/java/com/seckill/event/service/EventService.java`
- Modify: `backend/src/main/java/com/seckill/event/EventController.java`
- Test: `backend/src/test/java/com/seckill/event/PublicEventIT.java`

1. Write failing tests proving the endpoint returns only featured published events in configured order and applies a safe limit.
2. Run `./mvnw -Dtest=PublicEventIT test` and verify failure.
3. Add the mapper query, service method, and `GET /api/v1/events/featured` endpoint.
4. Run focused and full backend tests.
5. Commit with `feat(backend): add public featured events endpoint`.

### Task 4: Add frontend contracts and carousel state logic

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/events.ts`
- Modify: `frontend/src/api/admin.ts`
- Create: `frontend/src/utils/carousel.ts`
- Create: `frontend/src/utils/carousel.spec.ts`

1. Write failing Vitest cases for next/previous wrapping and safe empty state.
2. Run `pnpm test -- src/utils/carousel.spec.ts` and verify failure.
3. Add minimal pure index helpers and API/type fields.
4. Run the focused tests and type check.
5. Commit with `feat(frontend): add featured event contracts`.

### Task 5: Build the accessible carousel component

**Files:**
- Create: `frontend/src/components/FeaturedEventCarousel.vue`
- Create: `frontend/src/components/FeaturedEventCarousel.spec.ts`

1. Write failing component tests for rendering, controls, single-item behavior, and reduced-motion autoplay behavior.
2. Run the focused test and verify expected failures.
3. Implement centered 3:2 carousel, controls, focus/hover pause, keyboard support, and ARIA labels.
4. Run focused tests, type check, and lint.
5. Commit with `feat(frontend): add accessible event carousel`.

### Task 6: Integrate the homepage and landscape cards

**Files:**
- Modify: `frontend/src/views/EventListView.vue`
- Modify: `frontend/src/components/GenerativePoster.vue`

1. Add or update tests/assertions for independent featured loading and landscape fallback rendering.
2. Verify the test fails before changing production layout.
3. Fetch featured events independently, place carousel above the compact search section, and change cards/skeleton/fallbacks to 3:2.
4. Run frontend tests, type check, lint, and production build.
5. Commit with `feat(frontend): redesign event homepage with landscape media`.

### Task 7: Add admin curation controls

**Files:**
- Modify: `frontend/src/views/admin/AdminEventsView.vue`
- Modify: `frontend/src/api/types.ts`

1. Write failing validation/state tests for toggle and non-negative order normalization.
2. Verify expected failures.
3. Add the carousel switch, numeric order input, payload mapping, table indicators, and 3:2 preview guidance.
4. Run frontend verification commands.
5. Commit with `feat(frontend): add carousel curation controls`.

### Task 8: Final verification and documentation

**Files:**
- Modify: `docs/plans/2026-07-21-home-carousel-landscape-design.md` only if verified behavior differs.

1. Run full backend tests.
2. Run frontend tests, type check, lint, and build.
3. Manually verify 375px, 768px, 1024px, and 1440px layouts, keyboard controls, reduced motion, error fallbacks, and no image cropping.
4. Review `git diff --check` and `git status`.
5. Commit any final documentation corrections with `docs: finalize homepage carousel design`.
