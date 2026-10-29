<script lang="ts">
  import { onMount } from "svelte"
  import { pb, errorMessage } from "./lib/pb"
  import { store } from "./lib/store.svelte"
  import { router } from "./lib/router.svelte"
  import { theme } from "./lib/theme.svelte"
  import Login from "./components/Login.svelte"
  import Sidebar from "./components/Sidebar.svelte"
  import NotesGrid from "./components/NotesGrid.svelte"
  import NoteScreen from "./components/NoteScreen.svelte"
  import NotebookScreen from "./components/NotebookScreen.svelte"
  import SearchScreen from "./components/SearchScreen.svelte"
  import Lightbox from "./components/Lightbox.svelte"

  let signedIn = $state(pb.authStore.isValid)
  let loadError = $state<string | null>(null)
  let drawer = $state(false)

  async function start() {
    loadError = null
    try {
      // Renew the sign-in (they last 30 days), then load everything.
      await pb.collection("users").authRefresh()
      await store.load()
    } catch (e) {
      if (!pb.authStore.isValid) {
        signedIn = false
        return
      }
      loadError = errorMessage(e)
    }
  }

  onMount(() => {
    if (signedIn) start()
    return pb.authStore.onChange(() => {
      if (!pb.authStore.isValid && signedIn) {
        signedIn = false
        store.reset()
      }
    })
  })

  function signOut() {
    pb.authStore.clear()
    store.reset()
    signedIn = false
  }

  // Light/dark and the app's accent and background colours.
  $effect(() => theme.apply(store.prefs))

  // Close the drawer when moving to another screen.
  $effect(() => {
    router.route
    drawer = false
  })
</script>

{#if !signedIn}
  <Login
    onSignedIn={() => {
      signedIn = true
      start()
    }}
  />
{:else if loadError}
  <div class="center">
    <p>Couldn't load your notes: {loadError}</p>
    <button class="pill-btn" onclick={start}>Try again</button>
  </div>
{:else if !store.loaded}
  <div class="center"><p>Loading your notes…</p></div>
{:else}
  <div class="shell">
    <Sidebar open={drawer} onClose={() => (drawer = false)} {signOut} />
    <main>
      {#key router.route.name === "notebook" ? `nb:${router.route.id}` : router.route.name === "note" ? `note:${router.route.id}` : router.route.name}
        {#if router.route.name === "notes"}
          <NotesGrid archive={false} onMenu={() => (drawer = true)} />
        {:else if router.route.name === "archive"}
          <NotesGrid archive={true} onMenu={() => (drawer = true)} />
        {:else if router.route.name === "note"}
          <NoteScreen id={router.route.id} onMenu={() => (drawer = true)} />
        {:else if router.route.name === "notebook"}
          <NotebookScreen id={router.route.id} pageId={router.route.page} onMenu={() => (drawer = true)} />
        {:else if router.route.name === "search"}
          <SearchScreen q={router.route.q} onMenu={() => (drawer = true)} />
        {/if}
      {/key}
    </main>
  </div>
  <Lightbox />
{/if}

<style>
  .shell {
    display: flex;
    height: 100%;
  }
  main {
    flex: 1;
    min-width: 0;
    height: 100%;
    overflow: auto;
  }
  .center {
    height: 100%;
    display: grid;
    place-content: center;
    gap: 12px;
    text-align: center;
    color: var(--muted);
  }
</style>
