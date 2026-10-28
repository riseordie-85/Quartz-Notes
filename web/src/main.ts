import { mount } from "svelte"
import "./app.css"
import App from "./App.svelte"

document.fonts
  ?.load('24px "Material Symbols Rounded"', "home")
  .then((faces) => faces.length && document.documentElement.classList.add("icons-ready"))
  .catch(() => {})

mount(App, { target: document.getElementById("app")! })
