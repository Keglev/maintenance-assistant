<#--
  THE ONLY TEMPLATE THIS THEME OVERRIDES.

  Why it cannot be CSS: a back-link needs an href and a translated label, and the demo box needs
  four rows of text from the message bundle. CSS `content` can inject neither a working link nor a
  localised string, and the parent renders nothing here at all — there is no element to restyle.

  Why THIS file and not login.ftl: keycloak.v2 ships footer.ftl as a three-line empty macro whose
  own comment reads "You can override this file in your custom theme to declare a custom login
  footer element". It is the upstream extension point, it is rendered by template.ftl on EVERY
  login page (so the back-link is there on the password page, the OTP page and the error page
  alike), and it contains nothing to copy — so there is nothing to fall behind on the next
  Keycloak upgrade. Overriding login.ftl would have meant carrying a copy of the username/password
  form, the passkey import and the social-provider section, none of which this PR changes.
-->
<#macro content>
  <div class="wa-footer">
    <#-- Rendered only when the deployment supplied a URL. A "back to home" that goes nowhere, or
         worse, to the wrong environment, is worse than no link — see theme.properties. -->
    <#if properties.waHomeUrl?has_content>
      <#-- The arrow carries a class so the stylesheet can size it above the label: at the old size
           it read as punctuation and was the part of the control people missed. aria-hidden because
           the label already says where the link goes. -->
      <a class="wa-back-link" href="${properties.waHomeUrl}">
        <span class="wa-back-link-arrow" aria-hidden="true">&#8592;</span> ${msg("waBackToHome")}
      </a>
    </#if>

    <#-- The demo accounts. Off unless the deployment turns them on, because published credentials
         are a property of a public demo and not of a login theme. -->
    <#if properties.waShowDemoAccounts?? && properties.waShowDemoAccounts == "true">
      <div class="wa-demo" data-testid="wa-demo-accounts">
        <p class="wa-demo-heading">${msg("waDemoHeading")}</p>
        <p class="wa-demo-intro">${msg("waDemoIntro")} <code>demo1234</code></p>
        <ul class="wa-demo-list">
          <li><code>operator</code> <span>(${msg("waDemoOperator")})</span></li>
          <li><code>techniker</code> <span>(${msg("waDemoTechniker")})</span></li>
          <li><code>schichtleiter</code> <span>(${msg("waDemoSchichtleiter")})</span></li>
          <li><code>admin</code> <span>(${msg("waDemoAdmin")})</span></li>
        </ul>
      </div>
    </#if>
  </div>
</#macro>
