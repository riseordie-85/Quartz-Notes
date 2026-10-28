<script lang="ts">
  import { errorMessage, pb } from "../lib/pb"

  let { onSignedIn }: { onSignedIn: () => void } = $props()
  let email = $state("")
  let password = $state("")
  let busy = $state(false)
  let error = $state<string | null>(null)

  async function submit(e: SubmitEvent) {
    e.preventDefault()
    busy = true
    error = null
    try {
      await pb.collection("users").authWithPassword(email.trim(), password)
      onSignedIn()
    } catch (err) {
      error = errorMessage(err)
    } finally {
      busy = false
    }
  }
</script>

<main class="login">
  <form class="card" onsubmit={submit}>
    <img src="/icon-rounded.png" alt="" width="64" height="64" />
    <h1>Quartz Notes</h1>
    <p>Sign in with your sync account.</p>
    <label>
      <span>Email</span>
      <input type="email" autocomplete="username" bind:value={email} required />
    </label>
    <label>
      <span>Password</span>
      <input type="password" autocomplete="current-password" bind:value={password} required />
    </label>
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <button class="pill-btn" type="submit" disabled={busy || !email || !password}>{busy ? "Signing in…" : "Sign in"}</button>
  </form>
</main>

<style>
  .login {
    min-height: 100%;
    display: grid;
    place-items: center;
    padding: 16px;
  }
  form {
    width: min(380px, 100%);
    padding: 32px 28px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  img {
    border-radius: 16px;
    align-self: center;
  }
  h1 {
    margin: 4px 0 0;
    text-align: center;
    font-size: 26px;
  }
  p {
    margin: 0;
    text-align: center;
    color: var(--muted);
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
    font-size: 14px;
    color: var(--muted);
  }
  input {
    height: 46px;
    padding: 0 14px;
    border: 1px solid var(--divider);
    border-radius: 12px;
    background: var(--surface-2);
    font-size: 16px;
  }
  input:focus {
    outline: 2px solid var(--accent);
    border-color: transparent;
  }
  .error {
    color: #e53935;
  }
  button {
    margin-top: 6px;
    justify-content: center;
    height: 46px;
  }
</style>
