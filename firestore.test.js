const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const crypto = require("node:crypto");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const OWNER_EMAIL = "zaim9002@gmail.com";
const ADMIN_EMAIL = "admin1@gmail.com";
const USER_A_EMAIL = "alice@gmail.com";
const USER_B_EMAIL = "bob@gmail.com";

function sha256(str) {
  return crypto.createHash("sha256").update(str.trim().toLowerCase()).digest("hex");
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

test("Unauthenticated user: cannot read admins/{email}, config/github, or conversations", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("admins").doc(OWNER_EMAIL).get());
  await assertFails(unauthDb.collection("config").doc("github").get());
  await assertFails(unauthDb.collection("conversations").doc("a_b").get());
  await assertFails(unauthDb.collection("emailLookup").doc(sha256(USER_A_EMAIL)).get());
});

test("Any signed-in user: can read their own admins/{email} doc even if it does not exist", async () => {
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(aliceDb.collection("admins").doc(USER_A_EMAIL).get());
});

test("Signed-in user: cannot read another user's admins/{email} doc or list admins", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
  });
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  await assertFails(aliceDb.collection("admins").doc(ADMIN_EMAIL).get());
  await assertFails(aliceDb.collection("admins").get());
});

test("Unverified email token: cannot access admin-only config/github even if allowlisted", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("config").doc("github").set({ token: "secret-token" });
  });
  const unverifiedAdminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: false }).firestore();
  await assertFails(unverifiedAdminDb.collection("config").doc("github").get());
});

test("Non-admin signed-in user: cannot read or write config/github or config/appTheme", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("config").doc("github").set({ token: "secret-token" });
  });
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  await assertFails(aliceDb.collection("config").doc("github").get());
  await assertFails(aliceDb.collection("config").doc("github").set({ token: "hacked" }));
  await assertFails(aliceDb.collection("config").doc("appTheme").set({ id: "hacked" }));
});

test("Approved admin: can read config/github, cannot write config/github or admins", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("config").doc("github").set({ token: "shared-token" });
  });
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(adminDb.collection("admins").doc(ADMIN_EMAIL).get());
  await assertSucceeds(adminDb.collection("config").doc("github").get());
  await assertFails(adminDb.collection("config").doc("github").set({ token: "changed" }));
  await assertFails(adminDb.collection("admins").doc(USER_A_EMAIL).set({ approved: true }));
});

test("Owner zaim9002@gmail.com: always treated as admin and can read/write config/github and admins", async () => {
  const ownerDb = testEnv.authenticatedContext("uid_owner", { email: OWNER_EMAIL, email_verified: true }).firestore();
  await assertSucceeds(ownerDb.collection("admins").doc(ADMIN_EMAIL).set({ approved: true }));
  await assertSucceeds(ownerDb.collection("config").doc("github").set({ token: "ghp_owner_token" }));
  await assertSucceeds(ownerDb.collection("config").doc("github").get());
  await assertSucceeds(ownerDb.collection("admins").get());
});

test("emailLookup & usernames: exact get works, directory list denied, strict format enforced", async () => {
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const aliceHash = sha256(USER_A_EMAIL);

  // Can write hashed lookup without raw email
  await assertSucceeds(aliceDb.collection("emailLookup").doc(aliceHash).set({ uid: "uid_alice", updatedAt: 1000 }));
  // Cannot store raw email or wrong hash or spoof uid
  await assertFails(aliceDb.collection("emailLookup").doc(aliceHash).set({ uid: "uid_alice", email: USER_A_EMAIL }));
  await assertFails(aliceDb.collection("emailLookup").doc("not_a_sha256").set({ uid: "uid_alice" }));
  await assertFails(aliceDb.collection("emailLookup").doc(sha256(USER_B_EMAIL)).set({ uid: "uid_bob" }));
  // Exact get works, collection list denied
  await assertSucceeds(aliceDb.collection("emailLookup").doc(aliceHash).get());
  await assertFails(aliceDb.collection("emailLookup").get());

  // Usernames: 3..20 chars [a-z0-9_]
  await assertSucceeds(aliceDb.collection("usernames").doc("alice_dev").set({ uid: "uid_alice", createdAt: 1000 }));
  await assertFails(aliceDb.collection("usernames").doc("ab").set({ uid: "uid_alice" })); // too short
  await assertFails(aliceDb.collection("usernames").doc("a".repeat(21)).set({ uid: "uid_alice" })); // too long
  await assertFails(aliceDb.collection("usernames").doc("Alice").set({ uid: "uid_alice" })); // uppercase
  await assertFails(aliceDb.collection("usernames").get()); // listing denied
});

test("users/{uid}: non-admin cannot inject role/email/badges/frame/gif, large WebP <=220KB succeeds, admin manages badges/gif", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
  });
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  // Forbidden fields at create
  await assertFails(aliceDb.collection("users").doc("uid_alice").set({ uid: "uid_alice", role: "admin" }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").set({ uid: "uid_alice", email: USER_A_EMAIL }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").set({ uid: "uid_alice", badges: ["موثّق"] }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").set({ uid: "uid_alice", frame: "gold-admin" }));

  // Normal profile create with ~90KB WebP data URL succeeds
  const bigWebp = "data:image/webp;base64," + "A".repeat(90000);
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").set({
    uid: "uid_alice",
    username: "alice_dev",
    usernameLower: "alice_dev",
    usernameChosen: true,
    displayName: "Alice",
    bio: "Hello",
    photoURL: bigWebp,
    bannerURL: bigWebp,
    links: ["https://github.com/alice"],
    profileAccent: "#8B7CFF",
    hideCommentsCount: false,
    blocked: false,
    publisher: false,
    badges: [],
    frame: "",
    customTitle: "",
    profileGradient: "",
    createdAt: 1000,
    updatedAt: 1000,
  }));

  // Normal user can update their bio, links, accent, hideCommentsCount
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").update({
    bio: "Updated bio",
    links: ["https://github.com/alice", "https://t.me/alice"],
    profileAccent: "#FF4D8D",
    hideCommentsCount: true,
    updatedAt: 2000,
  }));

  // Normal user cannot tamper with badges, blocked, links (>3), non-https link, or GIF banner
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ badges: ["موثّق"] }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({ links: ["http://insecure.com"] }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({
    links: ["https://a.com", "https://b.com", "https://c.com", "https://d.com"],
  }));
  await assertFails(aliceDb.collection("users").doc("uid_alice").update({
    bannerURL: "data:image/gif;base64,R0lGODlhAQABAAAAACw=",
  }));

  // Admin CAN grant badges, frame, customTitle, and GIF banner to admin profile
  await assertSucceeds(aliceDb.collection("users").doc("uid_alice").get());
  await assertSucceeds(adminDb.collection("users").doc("uid_alice").update({
    badges: ["موثّق", "مبدع"],
    frame: "neon",
    customTitle: "مصمم مميز",
  }));
  await assertSucceeds(adminDb.collection("users").doc("uid_admin").set({
    uid: "uid_admin",
    displayName: "Admin",
    bannerURL: "data:image/gif;base64,R0lGODlhAQABAAAAACw=",
    badges: [],
    frame: "gold-admin",
    customTitle: "المشرف",
    profileGradient: "linear-gradient(135deg,#F59E0B,#EF4444)",
    blocked: false,
    publisher: false,
    createdAt: 1000,
  }));
});

test("conversations & messages: private to participants, read receipts restricted, personal block blocks messages, admins cannot snoop non-reported chats", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("users").doc("uid_alice").set({ uid: "uid_alice", blocked: false });
    await ctx.firestore().collection("users").doc("uid_bob").set({ uid: "uid_bob", blocked: false });
  });

  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext("uid_bob", { email: USER_B_EMAIL, email_verified: true }).firestore();
  const charlieDb = testEnv.authenticatedContext("uid_charlie", { email: "c@gmail.com", email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  const convId = "uid_alice_uid_bob";
  // Non-existent doc get works cleanly for participants check
  await assertSucceeds(aliceDb.collection("conversations").doc(convId).get());

  // Cannot create conversation spoofed without self or with wrong participant count
  await assertFails(charlieDb.collection("conversations").doc(convId).set({
    participants: ["uid_alice", "uid_bob"],
    participantA: "uid_alice",
    participantB: "uid_bob",
  }));

  // Alice creates conversation & sends message to Bob
  await assertSucceeds(aliceDb.collection("conversations").doc(convId).set({
    participants: ["uid_alice", "uid_bob"],
    participantA: "uid_alice",
    participantB: "uid_bob",
    lastMessage: "Hi Bob",
    lastAt: 1000,
    updatedAt: 1000,
    hasReportedMessage: false,
    unreadBy: { uid_alice: false, uid_bob: true },
  }));
  await assertSucceeds(aliceDb.collection("conversations").doc(convId).collection("messages").doc("m1").set({
    text: "Hi Bob",
    senderId: "uid_alice",
    createdAt: 1000,
    read: false,
  }));

  // Outsider & even Admin CANNOT read non-reported conversation or messages
  await assertFails(charlieDb.collection("conversations").doc(convId).get());
  await assertFails(adminDb.collection("conversations").doc(convId).get());
  await assertFails(adminDb.collection("conversations").doc(convId).collection("messages").doc("m1").get());

  // Bob can read and mark read=true, but cannot edit message text
  await assertSucceeds(bobDb.collection("conversations").doc(convId).collection("messages").doc("m1").get());
  await assertFails(bobDb.collection("conversations").doc(convId).collection("messages").doc("m1").update({ text: "Tampered" }));
  await assertSucceeds(bobDb.collection("conversations").doc(convId).collection("messages").doc("m1").update({ read: true }));

  // Bob blocks Alice -> Alice cannot send new messages to Bob
  await assertSucceeds(bobDb.collection("users").doc("uid_bob").collection("blockedUsers").doc("uid_alice").set({ uid: "uid_alice", createdAt: 1500 }));
  await assertFails(aliceDb.collection("conversations").doc(convId).collection("messages").doc("m2").set({
    text: "Hello again",
    senderId: "uid_alice",
    createdAt: 2000,
    read: false,
  }));

  // Once Bob reports & marks hasReportedMessage: true, Admin can read
  await assertSucceeds(bobDb.collection("conversations").doc(convId).update({ hasReportedMessage: true }));
  await assertSucceeds(adminDb.collection("conversations").doc(convId).get());
  await assertSucceeds(adminDb.collection("conversations").doc(convId).collection("messages").doc("m1").get());
  // Non-admin cannot unset hasReportedMessage back to false
  await assertFails(aliceDb.collection("conversations").doc(convId).update({ hasReportedMessage: false }));
});

test("conversations queries & legacy docs: whereArrayContains succeeds for member inbox/unread badge, unfiltered list fails, legacy conv without participantA/B works", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc("uid_alice").set({ uid: "uid_alice", blocked: false });
    await ctx.firestore().collection("users").doc("uid_bob").set({ uid: "uid_bob", blocked: false });
    await ctx.firestore().collection("conversations").doc("uid_alice_uid_bob").set({
      participants: ["uid_alice", "uid_bob"],
      lastMessage: "Legacy hello",
      lastAt: 1000,
      unreadBy: { uid_alice: true, uid_bob: false },
    });
    await ctx.firestore().collection("conversations").doc("uid_bob_uid_charlie").set({
      participants: ["uid_bob", "uid_charlie"],
      participantA: "uid_bob",
      participantB: "uid_charlie",
      lastMessage: "Private",
      lastAt: 2000,
    });
  });

  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const charlieDb = testEnv.authenticatedContext("uid_charlie", { email: "c@gmail.com", email_verified: true }).firestore();

  // Exact queries used by startUnreadMessagesListener & startMemberInboxListener MUST succeed
  await assertSucceeds(
    aliceDb.collection("conversations").where("participants", "array-contains", "uid_alice").get()
  );
  await assertSucceeds(
    aliceDb.collection("conversations").where("participants", "array-contains", "uid_alice").limit(100).get()
  );
  // Unfiltered list or querying someone else's chats MUST fail
  await assertFails(aliceDb.collection("conversations").get());
  await assertFails(
    charlieDb.collection("conversations").where("participants", "array-contains", "uid_alice").get()
  );

  // Reading & sending message in legacy conversation lacking participantA/participantB succeeds for participant
  await assertSucceeds(aliceDb.collection("conversations").doc("uid_alice_uid_bob").collection("messages").limit(50).get());
  await assertSucceeds(aliceDb.collection("conversations").doc("uid_alice_uid_bob").collection("messages").add({
    text: "Reply on legacy chat",
    senderId: "uid_alice",
    createdAt: 3000,
    read: false,
  }));
  // Spoofing senderId fails even for participant
  await assertFails(aliceDb.collection("conversations").doc("uid_alice_uid_bob").collection("messages").add({
    text: "Spoofed",
    senderId: "uid_bob",
    createdAt: 3001,
    read: false,
  }));
  // Marking unreadBy false works
  await assertSucceeds(aliceDb.collection("conversations").doc("uid_alice_uid_bob").update({
    "unreadBy.uid_alice": false,
  }));
});

test("legacy users/{uid} & claimInitialUsername: user can claim username on fresh or legacy doc without escalating privileges", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    // Legacy user document missing uid, having old email/role and null fields
    await ctx.firestore().collection("users").doc("uid_legacy").set({
      email: "legacy@gmail.com",
      role: "user",
      displayName: "Legacy User",
      links: null,
      badges: null,
      photoURL: "http://legacy.example.com/p.jpg",
    });
  });

  const legacyDb = testEnv.authenticatedContext("uid_legacy", { email: "legacy@gmail.com", email_verified: true }).firestore();
  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();

  // Claiming username doc + updating legacy user doc with merge succeeds
  await assertSucceeds(legacyDb.collection("usernames").doc("legacy_user").set({ uid: "uid_legacy", createdAt: 1000 }));
  await assertSucceeds(legacyDb.collection("users").doc("uid_legacy").set({
    uid: "uid_legacy",
    username: "legacy_user",
    usernameLower: "legacy_user",
    usernameChosen: true,
    displayName: "Legacy Updated",
    updatedAt: 2000,
  }, { merge: true }));

  // Idempotent update on own username reservation succeeds; Alice cannot steal it
  await assertSucceeds(legacyDb.collection("usernames").doc("legacy_user").set({ uid: "uid_legacy", createdAt: 2000 }));
  await assertFails(aliceDb.collection("usernames").doc("legacy_user").set({ uid: "uid_alice", createdAt: 3000 }));

  // Legacy user still CANNOT modify role, blocked, publisher, badges, or email
  await assertFails(legacyDb.collection("users").doc("uid_legacy").update({ role: "admin" }));
  await assertFails(legacyDb.collection("users").doc("uid_legacy").update({ publisher: true }));
  await assertFails(legacyDb.collection("users").doc("uid_legacy").update({ email: "hacked@gmail.com" }));
});

test("submissions, notifications & auditLog: open submissions for normal users, notifications recipient-only, auditLog admin append-only", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_EMAIL).set({ approved: true });
    await ctx.firestore().collection("users").doc("uid_alice").set({ uid: "uid_alice", blocked: false, publisher: false });
    await ctx.firestore().collection("users").doc("uid_blocked").set({ uid: "uid_blocked", blocked: true });
    await ctx.firestore().collection("themes").doc("theme_pinned").set({ pinned: true, ratingAvg: 4.5, ratingCount: 2 });
  });

  const aliceDb = testEnv.authenticatedContext("uid_alice", { email: USER_A_EMAIL, email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext("uid_bob", { email: USER_B_EMAIL, email_verified: true }).firestore();
  const blockedDb = testEnv.authenticatedContext("uid_blocked", { email: "bad@gmail.com", email_verified: true }).firestore();
  const adminDb = testEnv.authenticatedContext("uid_admin", { email: ADMIN_EMAIL, email_verified: true }).firestore();

  // Rating merge on already-pinned theme succeeds
  await assertSucceeds(aliceDb.collection("themes").doc("theme_pinned").set({
    ratingAvg: 4.67,
    ratingCount: 3,
    updatedAt: 1500,
  }, { merge: true }));

  // Regular user (publisher: false) CAN submit a theme
  await assertSucceeds(aliceDb.collection("submissions").doc("sub1").set({
    publisherUid: "uid_alice",
    title: "Dark Cyber",
    status: "pending",
    createdAt: 1000,
  }));
  // Client queries for submissions work
  await assertSucceeds(aliceDb.collection("submissions").where("publisherUid", "==", "uid_alice").get());
  await assertSucceeds(adminDb.collection("submissions").where("status", "==", "pending").get());
  // Blocked user cannot submit; regular user cannot self-approve
  await assertFails(blockedDb.collection("submissions").doc("sub2").set({
    publisherUid: "uid_blocked",
    title: "Spam",
    status: "pending",
  }));
  await assertFails(aliceDb.collection("submissions").doc("sub3").set({
    publisherUid: "uid_alice",
    title: "Bypass",
    status: "approved",
  }));
  await assertFails(aliceDb.collection("submissions").doc("sub1").update({ status: "approved" }));
  // Other user cannot read Alice's submission
  await assertFails(bobDb.collection("submissions").doc("sub1").get());

  // Admin approves submission, writes notification for Alice, and logs auditLog
  await assertSucceeds(adminDb.collection("submissions").doc("sub1").update({ status: "approved" }));
  await assertSucceeds(adminDb.collection("notifications").doc("uid_alice").collection("items").doc("n1").set({
    type: "submission_approved",
    title: "تم قبول ثيمك",
    body: "تم نشر Dark Cyber",
    read: false,
    createdAt: 2000,
  }));
  await assertSucceeds(adminDb.collection("auditLog").doc("log1").set({
    actorUid: "uid_admin",
    action: "approve_submission",
    targetId: "sub1",
    createdAt: 2000,
  }));

  // Non-admin cannot forge notifications or auditLog, and admin cannot delete auditLog
  await assertFails(aliceDb.collection("notifications").doc("uid_alice").collection("items").doc("n2").set({ title: "fake" }));
  await assertFails(bobDb.collection("notifications").doc("uid_alice").collection("items").doc("n1").get());
  await assertSucceeds(aliceDb.collection("notifications").doc("uid_alice").collection("items").doc("n1").update({ read: true }));
  await assertFails(adminDb.collection("auditLog").doc("log1").delete());
});

