(function () {
  "use strict";

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var textarea = document.createElement("textarea");
      textarea.value = text;
      textarea.setAttribute("readonly", "");
      textarea.className = "sr-only";
      document.body.appendChild(textarea);
      textarea.select();
      try {
        var ok = document.execCommand("copy");
        document.body.removeChild(textarea);
        if (ok) {
          resolve();
        } else {
          reject(new Error("Copy command rejected"));
        }
      } catch (err) {
        document.body.removeChild(textarea);
        reject(err);
      }
    });
  }

  function announce(message) {
    var statusEl = document.getElementById("demo-action-status");
    if (!statusEl) {
      return;
    }
    statusEl.textContent = message;
    statusEl.classList.remove("is-hidden");
  }

  function flashButtonLabel(button, temporaryLabel) {
    var original = button.getAttribute("data-original-label") || button.textContent;
    button.setAttribute("data-original-label", original);
    button.textContent = temporaryLabel;
    button.disabled = true;
    window.setTimeout(function () {
      button.textContent = original;
      button.disabled = false;
    }, 1600);
  }

  document.addEventListener("DOMContentLoaded", function () {
    var copyButtons = document.querySelectorAll("[data-copy-value]");
    copyButtons.forEach(function (button) {
      button.addEventListener("click", function () {
        var value = button.getAttribute("data-copy-value") || "";
        var label = button.getAttribute("data-copy-label") || "Credentials";
        copyText(value)
          .then(function () {
            flashButtonLabel(button, "Copied!");
            announce(label + " copied to clipboard.");
          })
          .catch(function () {
            announce("Unable to access clipboard automatically. Please select and copy the displayed text.");
          });
      });
    });

    var fillButtons = document.querySelectorAll("[data-fill-email][data-fill-password]");
    fillButtons.forEach(function (button) {
      button.addEventListener("click", function () {
        var email = button.getAttribute("data-fill-email") || "";
        var password = button.getAttribute("data-fill-password") || "";
        var roleName = button.getAttribute("data-fill-role") || "Demo";

        var usernameInput = document.getElementById("username");
        var passwordInput = document.getElementById("password");
        var submitButton = document.getElementById("login-submit-btn");

        if (usernameInput && passwordInput) {
          usernameInput.value = email;
          passwordInput.value = password;
          announce(
            "Filled " + roleName + " credentials into the sign-in form. Click \"Sign In\" to authenticate."
          );
          if (submitButton) {
            submitButton.focus();
          } else {
            usernameInput.focus();
          }
          var formCard = document.getElementById("login-form-card");
          if (formCard && typeof formCard.scrollIntoView === "function") {
            formCard.scrollIntoView({ behavior: "smooth", block: "center" });
          }
        }
      });
    });
  });
})();
