// Firestore security-rules tests. All data below is synthetic.
// Run: npm test   (starts the Firestore emulator via firebase-tools)
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  initializeTestEnvironment, assertSucceeds, assertFails,
} from '@firebase/rules-unit-testing';
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, query, where, addDoc,
} from 'firebase/firestore';

const here = dirname(fileURLToPath(import.meta.url));
const rules = readFileSync(join(here, '..', 'firestore.rules'), 'utf8');

const PHONE_A = '9876543210';   // manager A
const PHONE_B = '9123456780';   // manager B
const CODE_A = 'AAAAAA';        // fleet codes are opaque to the rules
const CODE_B = 'BBBBBB';

let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-smarthelm', firestore: { rules } });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

// ── contexts ──
const rider  = (uid) => env.authenticatedContext(uid, { firebase: { sign_in_provider: 'anonymous' } }).firestore();
const mgr    = (uid, phone) => env.authenticatedContext(uid, {
  phone_number: '+91' + phone, firebase: { sign_in_provider: 'phone' } }).firestore();
const anon   = () => env.unauthenticatedContext().firestore();

// ── seeding (rules disabled) ──
const seed = (fn) => env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));
const seedFleet = (code, uid, phone) => seed((db) =>
  setDoc(doc(db, 'fleets', code), { managerUid: uid, managerPhone: phone, emergencyContact: '', updatedAt: 'x' }));
const seedRider = (id, owner, managerId) => seed((db) =>
  setDoc(doc(db, 'riders', id), { ownerUid: owner, managerId, riderName: 'Test Rider', eyeState: 'OPEN', perclos: 3 }));

// ───────────────────────── riders: ownership ─────────────────────────
test('rider can create own doc claiming their uid', async () => {
  await assertSucceeds(setDoc(doc(rider('r1'), 'riders', 'dev1'),
    { ownerUid: 'r1', managerId: CODE_A, riderName: 'Asha', connected: false }));
});

test('rider cannot create a doc claiming another uid', async () => {
  await assertFails(setDoc(doc(rider('r1'), 'riders', 'dev1'), { ownerUid: 'someone-else', managerId: CODE_A }));
});

test('create without ownerUid is denied', async () => {
  await assertFails(setDoc(doc(rider('r1'), 'riders', 'dev1'), { managerId: CODE_A, riderName: 'Asha' }));
});

test('unauthenticated users cannot write', async () => {
  await assertFails(setDoc(doc(anon(), 'riders', 'dev1'), { ownerUid: 'x', managerId: CODE_A }));
});

test('owner can merge-update; other anonymous user cannot read/update/delete', async () => {
  await seedRider('dev1', 'r1', CODE_A);
  await assertSucceeds(setDoc(doc(rider('r1'), 'riders', 'dev1'), { ownerUid: 'r1', perclos: 12, eyeState: 'CLOSED' }, { merge: true }));
  await assertSucceeds(getDoc(doc(rider('r1'), 'riders', 'dev1')));
  await assertFails(getDoc(doc(rider('attacker'), 'riders', 'dev1')));
  await assertFails(updateDoc(doc(rider('attacker'), 'riders', 'dev1'), { perclos: 99 }));
  await assertFails(deleteDoc(doc(rider('attacker'), 'riders', 'dev1')));
});

test('owner can delete own doc (positive control for the delete rule)', async () => {
  await seedRider('dev1', 'r1', CODE_A);
  await assertSucceeds(deleteDoc(doc(rider('r1'), 'riders', 'dev1')));
});

test('owner cannot hand the doc to another uid', async () => {
  await seedRider('dev1', 'r1', CODE_A);
  await assertFails(updateDoc(doc(rider('r1'), 'riders', 'dev1'), { ownerUid: 'attacker' }));
});

test('a legacy doc with no owner can be claimed once; strangers still cannot read it', async () => {
  await seed((db) => setDoc(doc(db, 'riders', 'old1'), { riderName: 'Legacy', managerId: CODE_A, eyeState: 'OPEN' }));
  await assertFails(getDoc(doc(rider('r9'), 'riders', 'old1')));
  await assertSucceeds(updateDoc(doc(rider('r1'), 'riders', 'old1'), { ownerUid: 'r1' }));
  await assertFails(updateDoc(doc(rider('r2'), 'riders', 'old1'), { ownerUid: 'r2' }));
});

test('field validation still rejects bad values', async () => {
  await seedRider('dev1', 'r1', CODE_A);
  await assertFails(updateDoc(doc(rider('r1'), 'riders', 'dev1'), { perclos: 150 }));
  await assertFails(updateDoc(doc(rider('r1'), 'riders', 'dev1'), { eyeState: 'HACKED' }));
  await assertFails(updateDoc(doc(rider('r1'), 'riders', 'dev1'), { emergencyContact: '<script>' }));
  await assertSucceeds(updateDoc(doc(rider('r1'), 'riders', 'dev1'), { emergencyContact: '+919876543210' }));
});

// ───────────────────────── riders: manager reads ─────────────────────────
test('anonymous user cannot list all riders, nor filter by a fleet they do not own', async () => {
  await seedRider('dev1', 'r1', CODE_A);
  await assertFails(getDocs(collection(rider('attacker'), 'riders')));
  await assertFails(getDocs(query(collection(rider('attacker'), 'riders'), where('managerId', '==', CODE_A))));
});

test('manager can list only riders of their own fleet (filtered query)', async () => {
  await seedFleet(CODE_A, 'mA', PHONE_A);
  await seedRider('dev1', 'r1', CODE_A);
  await seedRider('dev2', 'r2', CODE_B);
  const snap = await assertSucceeds(getDocs(query(collection(mgr('mA', PHONE_A), 'riders'), where('managerId', '==', CODE_A))));
  if (snap.size !== 1) throw new Error('expected exactly 1 rider, got ' + snap.size);
});

test('manager cannot run an unfiltered list, nor read another fleet', async () => {
  await seedFleet(CODE_A, 'mA', PHONE_A);
  await seedFleet(CODE_B, 'mB', PHONE_B);
  await seedRider('dev1', 'r1', CODE_A);
  await seedRider('dev2', 'r2', CODE_B);
  await assertFails(getDocs(collection(mgr('mA', PHONE_A), 'riders')));
  await assertFails(getDocs(query(collection(mgr('mA', PHONE_A), 'riders'), where('managerId', '==', CODE_B))));
  await assertFails(getDoc(doc(mgr('mA', PHONE_A), 'riders', 'dev2')));
});

test('an anonymous (non-phone) user with a copied managerUid still cannot read the fleet', async () => {
  await seedFleet(CODE_A, 'mA', PHONE_A);
  await seedRider('dev1', 'r1', CODE_A);
  // same uid string but signed in anonymously, not by phone
  const db = env.authenticatedContext('mA', { firebase: { sign_in_provider: 'anonymous' } }).firestore();
  await assertFails(getDocs(query(collection(db, 'riders'), where('managerId', '==', CODE_A))));
});

// ───────────────────────── fleets ─────────────────────────
test('rider app can read a fleet by code but cannot list fleets', async () => {
  await seedFleet(CODE_A, 'mA', PHONE_A);
  await assertSucceeds(getDoc(doc(rider('r1'), 'fleets', CODE_A)));
  await assertFails(getDocs(collection(rider('r1'), 'fleets')));
  await assertFails(getDoc(doc(anon(), 'fleets', CODE_A)));
});

test('manager registers own fleet with a matching verified phone', async () => {
  await assertSucceeds(setDoc(doc(mgr('mA', PHONE_A), 'fleets', CODE_A),
    { managerUid: 'mA', managerPhone: PHONE_A, emergencyContact: '9000000001', updatedAt: '2026-01-01T00:00:00Z' }, { merge: true }));
});

test('cannot register a fleet for a phone number that is not yours', async () => {
  await assertFails(setDoc(doc(mgr('mA', PHONE_A), 'fleets', CODE_A),
    { managerUid: 'mA', managerPhone: PHONE_B, updatedAt: 'x' }, { merge: true }));
});

test('anonymous users cannot create or modify fleets', async () => {
  await assertFails(setDoc(doc(rider('r1'), 'fleets', CODE_A), { managerUid: 'r1', managerPhone: PHONE_A, updatedAt: 'x' }));
  await seedFleet(CODE_A, 'mA', PHONE_A);
  await assertFails(updateDoc(doc(rider('r1'), 'fleets', CODE_A), { emergencyContact: '9999999999' }));
});

test("another manager cannot overwrite someone else's fleet", async () => {
  await seedFleet(CODE_A, 'mA', PHONE_A);
  await assertFails(setDoc(doc(mgr('mB', PHONE_B), 'fleets', CODE_A),
    { managerUid: 'mB', managerPhone: PHONE_B, updatedAt: 'x' }, { merge: true }));
});

test('legacy fleet doc (no managerUid) can be claimed only by its real phone owner', async () => {
  await seed((db) => setDoc(doc(db, 'fleets', CODE_A), { managerPhone: PHONE_A, emergencyContact: '', updatedAt: 'x' }));
  await assertFails(setDoc(doc(mgr('mB', PHONE_B), 'fleets', CODE_A),
    { managerUid: 'mB', managerPhone: PHONE_B, updatedAt: 'x' }, { merge: true }));
  await assertSucceeds(setDoc(doc(mgr('mA', PHONE_A), 'fleets', CODE_A),
    { managerUid: 'mA', managerPhone: PHONE_A, updatedAt: 'x' }, { merge: true }));
});

test('fleet extra fields and deletes are rejected', async () => {
  await assertFails(setDoc(doc(mgr('mA', PHONE_A), 'fleets', CODE_A),
    { managerUid: 'mA', managerPhone: PHONE_A, updatedAt: 'x', isAdmin: true }));
  await seedFleet(CODE_A, 'mA', PHONE_A);
  await assertFails(deleteDoc(doc(mgr('mA', PHONE_A), 'fleets', CODE_A)));
});

// ───────────────────────── alerts ─────────────────────────
test('alerts: owner appends, manager reads, nobody edits or deletes, strangers blocked', async () => {
  await seedFleet(CODE_A, 'mA', PHONE_A);
  await seedRider('dev1', 'r1', CODE_A);
  const a = await assertSucceeds(addDoc(collection(rider('r1'), 'riders', 'dev1', 'alerts'), { type: 'PERCLOS' }));
  await assertSucceeds(getDoc(doc(mgr('mA', PHONE_A), 'riders', 'dev1', 'alerts', a.id)));
  await assertFails(getDoc(doc(rider('attacker'), 'riders', 'dev1', 'alerts', a.id)));
  await assertFails(addDoc(collection(rider('attacker'), 'riders', 'dev1', 'alerts'), { type: 'FAKE' }));
  await assertFails(updateDoc(doc(rider('r1'), 'riders', 'dev1', 'alerts', a.id), { type: 'X' }));
  await assertFails(deleteDoc(doc(rider('r1'), 'riders', 'dev1', 'alerts', a.id)));
});
