// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.httpd.auth.container;

import com.google.gerrit.common.PageLinks;
import com.google.gerrit.httpd.HtmlDomUtil;
import com.google.gerrit.httpd.WebSession;
import com.google.gerrit.server.account.AccountException;
import com.google.gerrit.server.account.AccountManager;
import com.google.gerrit.server.account.AuthRequest;
import com.google.gerrit.server.account.AuthResult;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gwtexpui.server.CacheHeaders;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import org.eclipse.jgit.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;

import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Initializes the user session if HTTP authentication is enabled.
 * <p>
 * If HTTP authentication has been enabled this servlet binds to {@code /login/}
 * and initializes the user session based on user information contained in the
 * HTTP request.
 */
@Singleton
class HttpLoginServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log =
      LoggerFactory.getLogger(HttpLoginServlet.class);

  private static final String AUTHORIZATION = "Authorization";
  private final Provider<WebSession> webSession;
  private final Provider<String> urlProvider;
  private final AccountManager accountManager;
  private final String loginHeader;

  @Inject
  HttpLoginServlet(final AuthConfig authConfig,
      final Provider<WebSession> webSession,
      @CanonicalWebUrl @Nullable final Provider<String> urlProvider,
      final AccountManager accountManager) {
    this.webSession = webSession;
    this.urlProvider = urlProvider;
    this.accountManager = accountManager;

    final String hdr = authConfig.getLoginHttpHeader();
    this.loginHeader = hdr != null && !hdr.equals("") ? hdr : AUTHORIZATION;
  }

  @Override
  protected void doGet(final HttpServletRequest req,
      final HttpServletResponse rsp) throws ServletException, IOException {
    final String token = getToken(req);
    if ("/logout".equals(token) || "/signout".equals(token)) {
      req.getRequestDispatcher("/logout").forward(req, rsp);
      return;
    }

    CacheHeaders.setNotCacheable(rsp);
    final String user = getRemoteUser(req);
    if (user == null || "".equals(user)) {
      log.error("Unable to authenticate user by " + loginHeader
          + " request header.  Check container or server configuration.");

      final Document doc = HtmlDomUtil.parseFile( //
          HttpLoginServlet.class, "ConfigurationError.html");

      replace(doc, "loginHeader", loginHeader);
      replace(doc, "ServerName", req.getServerName());
      replace(doc, "ServerPort", ":" + req.getServerPort());
      replace(doc, "ContextPath", req.getContextPath());

      final byte[] bin = HtmlDomUtil.toUTF8(doc);
      rsp.setStatus(HttpServletResponse.SC_FORBIDDEN);
      rsp.setContentType("text/html");
      rsp.setCharacterEncoding("UTF-8");
      rsp.setContentLength(bin.length);
      final ServletOutputStream out = rsp.getOutputStream();
      try {
        out.write(bin);
      } finally {
        out.flush();
        out.close();
      }
      return;
    }

    final AuthRequest areq = AuthRequest.forUser(user);
    final AuthResult arsp;
    try {
      arsp = accountManager.authenticate(areq);
    } catch (AccountException e) {
      log.error("Unable to authenticate user \"" + user + "\"", e);
      rsp.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    final StringBuilder rdr = new StringBuilder();
    rdr.append(urlProvider.get());
    rdr.append('#');
    if (arsp.isNew() && !token.startsWith(PageLinks.REGISTER + "/")) {
      rdr.append(PageLinks.REGISTER);
    }
    rdr.append(token);

    webSession.get().login(arsp, true /* persistent cookie */);
    rsp.sendRedirect(rdr.toString());
  }

  private void replace(Document doc, String name, String value) {
    Element e = HtmlDomUtil.find(doc, name);
    if (e != null) {
      e.setTextContent(value);
    } else {
      replaceByClass(doc, name, value);
    }
  }

  private void replaceByClass(Node parent, String name, String value) {
    final NodeList list = parent.getChildNodes();
    for (int i = 0; i < list.getLength(); i++) {
      final Node n = list.item(i);
      if (n instanceof Element) {
        final Element e = (Element) n;
        if (name.equals(e.getAttribute("class"))) {
          e.setTextContent(value);
        }
      }
      replaceByClass(n, name, value);
    }
  }

  private String getToken(final HttpServletRequest req) {
    String token = req.getPathInfo();
    if (token == null || token.isEmpty()) {
      token = PageLinks.MINE;
    } else if (!token.startsWith("/")) {
      token = "/" + token;
    }
    return token;
  }

  private String getRemoteUser(final HttpServletRequest req) {
    if (AUTHORIZATION.equals(loginHeader)) {
      final String user = req.getRemoteUser();
      if (user != null && !"".equals(user)) {
        // The container performed the authentication, and has the user
        // identity already decoded for us. Honor that as we have been
        // configured to honor HTTP authentication.
        //
        return user;
      }

      // If the container didn't do the authentication we might
      // have done it in the front-end web server. Try to split
      // the identity out of the Authorization header and honor it.
      //
      String auth = req.getHeader(AUTHORIZATION);
      if (auth == null || "".equals(auth)) {
        return null;

      } else if (auth.startsWith("Basic ")) {
        auth = auth.substring("Basic ".length());
        auth = new String(Base64.decode(auth));
        final int c = auth.indexOf(':');
        return c > 0 ? auth.substring(0, c) : null;

      } else if (auth.startsWith("Digest ")) {
        final int u = auth.indexOf("username=\"");
        if (u <= 0) {
          return null;
        }
        auth = auth.substring(u + 10);
        final int e = auth.indexOf('"');
        return e > 0 ? auth.substring(0, auth.indexOf('"')) : null;

      } else {
        return null;
      }
    } else {
      // Nonstandard HTTP header. We have been told to trust this
      // header blindly as-is.
      //
      final String user = req.getHeader(loginHeader);
      return user != null && !"".equals(user) ? user : null;
    }
  }
}
